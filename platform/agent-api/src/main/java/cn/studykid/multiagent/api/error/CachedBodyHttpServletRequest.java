package cn.studykid.multiagent.api.error;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * <b>有界</b>的请求体缓存包装器：让「异常到达时还能回看这次请求带了什么」成为可能。
 *
 * <p><b>为什么需要它</b>：JSON 请求的载荷全在 body 里，而 body 是<b>一次性</b>的——
 * 消息转换器读完它之后，流里就没有东西了。等到异常处理器想记「这次请求带了什么」，
 * 原始流早已见底，只能记下一个空壳。因此必须在<b>入口处</b>（body 被读之前）
 * 把读过的字节顺手存一份。
 *
 * <p><b>为什么不用 Spring 的 {@code ContentCachingRequestWrapper}</b>：
 * 它<b>无上限</b>——客户端发一个 100 MB 的 JSON，服务器就把它整个留在堆里。
 * 本类只留前 {@link #CACHE_LIMIT} 字节，超出部分<b>照常透传</b>（业务读取不受影响），
 * 只是不再留存，并在 {@link #isTruncated()} 上留痕。
 *
 * <p><b>三条不可退让的纪律</b>：
 * <ol>
 *   <li><b>只缓存、不改变语义</b>：透传的仍是原始字节流，
 *       控制器读到的内容与未包装时完全一致；</li>
 *   <li><b>缓存有上限</b>：超过 {@link #CACHE_LIMIT} 立即停止留存，
 *       避免「为了排障把内存吃光」——那正是排障本身变成故障的方式；</li>
 *   <li><b>流只能读一次</b>：与 Servlet 规范一致。
 *       {@link #getInputStream()} 每次返回<b>同一个</b>实例，
 *       否则二次调用会把同一段字节缓存两遍（缓存里出现重复的 body）。</li>
 * </ol>
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /** 缓存上限：8 KiB。够看清一次请求的字段结构，又不至于把内存吃光。 */
    static final int CACHE_LIMIT = 8 * 1024;

    /** 已缓存的字节。 */
    private final ByteArrayOutputStream cache = new ByteArrayOutputStream();

    /** 是否因超出上限而截断。 */
    private boolean truncated;

    /** 请求体是否被读取过（区分「读过但是空的」与「根本没读过」）。 */
    private boolean bodyProvided;

    /** 缓存流：只创建一次，避免二次调用 {@code getInputStream()} 时重复缓存。 */
    private ServletInputStream inputStream;

    /** 缓存 reader：同样只创建一次。 */
    private BufferedReader reader;

    CachedBodyHttpServletRequest(HttpServletRequest request) {
        super(request);
    }

    /** 已缓存的字节（未读到内容时为空数组）。 */
    byte[] cachedBody() {
        return cache.toByteArray();
    }

    /** 请求体是否被读取过。 */
    boolean isBodyProvided() {
        return bodyProvided;
    }

    /** 缓存是否因超出上限而截断。 */
    boolean isTruncated() {
        return truncated;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        bodyProvided = true;
        if (inputStream == null) {
            inputStream = new CachingInputStream(super.getInputStream());
        }
        return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        bodyProvided = true;
        if (reader == null) {
            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }
        return reader;
    }

    /** 请求字符集：取不到或非法时回落 UTF-8（JSON 的默认字符集）。 */
    private Charset charset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException e) {
            // 非法字符集名不得让异常路径再抛一次：日志辅助代码绝不能把原异常顶掉。
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 边透传边缓存的输入流。
     *
     * <p>缓存只发生在「被读过的字节」上——控制器没读过的部分不会凭空出现，
     * 这与「异常发生时 body 已被读完」的现实相符。
     */
    private final class CachingInputStream extends ServletInputStream {

        private final ServletInputStream delegate;

        private CachingInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                capture(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int read = delegate.read(buffer, off, len);
            if (read > 0) {
                capture(buffer, off, read);
            }
            return read;
        }

        private void capture(int b) {
            if (cache.size() < CACHE_LIMIT) {
                cache.write(b);
            } else {
                truncated = true;
            }
        }

        private void capture(byte[] buffer, int off, int length) {
            int room = CACHE_LIMIT - cache.size();
            if (room <= 0) {
                truncated = true;
                return;
            }
            int taken = Math.min(room, length);
            cache.write(buffer, off, taken);
            if (taken < length) {
                truncated = true;
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
