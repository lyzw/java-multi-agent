package cn.studykid.multiagent.api.platform;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.persistence.DictionaryPort;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台字典端点：把前端下拉枚举从「代码常量」变成「可维护的数据」。
 *
 * <p><b>权限</b>：读 {@code platform.resource.read}；写 {@code platform.resource.manage}。
 * 刻意复用冻结矩阵里既有的 resource.* 权限，<b>不新增权限码</b>——
 * 新增权限要动冻结矩阵与 6 个角色的授予关系，成本与风险都高于复用。
 *
 * <p><b>⚠️ 内置字典（built_in=true）的三条硬约束</b>：
 * <ol>
 *   <li>禁止删除（一律停用，物理删除会让已落库的历史取值悬空）；</li>
 *   <li>禁止改 {@code item_code}（后端按 Java 枚举校验的正是它）；</li>
 *   <li>中文标签 / 排序 / 启用状态<b>可以</b>改——这才是字典表要解决的事。</li>
 * </ol>
 * 违反前两条返回 <b>409</b>（非法操作，不是服务故障），并给出可操作提示。
 *
 * <p><b>⚠️ 诚实边界</b>：对内置字典新增一项，若后端枚举未同步，
 * 写入业务表时仍会被后端拒绝。字典页只负责字典，不替后端背书。
 */
@RestController
@RequestMapping("/api/platform/v1/dictionaries")
public class PlatformDictionaryController {

    /** 字典编码：小写点分，如 {@code http.method}。 */
    private static final Pattern DICT_CODE = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*");

    /** 取值编码：后端枚举校验的就是它，字符集刻意收紧。 */
    private static final Pattern ITEM_CODE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PlatformSecurity security;
    private final DictionaryPort dictionaries;

    public PlatformDictionaryController(PlatformSecurity security, DictionaryPort dictionaries) {
        this.security = security;
        this.dictionaries = dictionaries;
    }

    /**
     * 列出全部字典及其取值。
     *
     * @param enabledOnly true 时只回启用项（前端下拉用）；管理页默认传 false 以便维护停用项
     */
    @GetMapping
    public List<DictionaryView> list(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(name = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);

        return dictionaries.listDictionaries().stream()
                .filter(d -> !enabledOnly || d.enabled())
                .map(d -> toView(d, enabledOnly))
                .toList();
    }

    @GetMapping("/{dictCode}")
    public DictionaryView get(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable("dictCode") String dictCode,
            @RequestParam(name = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return toView(requireDictionary(dictCode), enabledOnly);
    }

    /** 新建字典（恒为非内置；内置字典由迁移脚本播种，不接受应用层创建）。 */
    @PostMapping
    public DictionaryView create(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody CreateDictionaryRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);

        String code = trimToNull(request.dictCode());
        if (code == null || !DICT_CODE.matcher(code).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "字典编码须为小写点分形式，如 rag.subject");
        }
        String name = trimToNull(request.dictName());
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字典名称不能为空");
        }
        if (dictionaries.findDictionary(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "字典编码已存在：" + code);
        }

        dictionaries.insertDictionary(new DictionaryPort.DictionaryRow(
                code, name, request.description(), false, true,
                request.sortOrder() == null ? 100 : request.sortOrder()));

        return toView(requireDictionary(code), false);
    }

    /** 更新字典的名称 / 说明 / 排序 / 启用（编码不可改）。 */
    @PutMapping("/{dictCode}")
    public DictionaryView update(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable("dictCode") String dictCode,
            @RequestBody UpdateDictionaryRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);

        DictionaryPort.DictionaryRow current = requireDictionary(dictCode);
        String name = trimToNull(request.dictName());
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字典名称不能为空");
        }

        dictionaries.updateDictionary(new DictionaryPort.DictionaryRow(
                current.dictCode(),
                name,
                request.description() == null ? current.description() : request.description(),
                current.builtIn(),
                request.enabled() == null ? current.enabled() : request.enabled(),
                request.sortOrder() == null ? current.sortOrder() : request.sortOrder()));

        return toView(requireDictionary(dictCode), false);
    }

    /** 写入 / 更新一项（按 dict_code + item_code 唯一键 upsert）。 */
    @PostMapping("/{dictCode}/items")
    public ItemView upsertItem(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable("dictCode") String dictCode,
            @RequestBody UpsertItemRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);

        DictionaryPort.DictionaryRow dict = requireDictionary(dictCode);
        String itemCode = trimToNull(request.itemCode());
        if (itemCode == null || !ITEM_CODE.matcher(itemCode).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "取值编码只允许字母、数字、点、下划线与短横线");
        }
        String label = trimToNull(request.itemLabel());
        if (label == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "展示标签不能为空");
        }

        boolean builtIn = dict.builtIn();
        int sortOrder = request.sortOrder() == null ? 100 : request.sortOrder();
        boolean enabled = request.enabled() == null || request.enabled();

        dictionaries.upsertItem(new DictionaryPort.DictionaryItemRow(
                dictCode, itemCode, label, sortOrder, builtIn, enabled));

        return ItemView.from(requireItem(dictCode, itemCode));
    }

    /**
     * 更新一项的标签 / 排序 / 启用状态。
     *
     * <p>⚠️ 内置项的 {@code item_code} <b>不可改</b>：后端按 Java 枚举校验的正是它，
     * 改了会让已落库的历史行取值悬空。请求体里带 {@code newItemCode} 时对内置项返回 409。
     */
    @PutMapping("/{dictCode}/items/{itemCode}")
    public ItemView updateItem(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable("dictCode") String dictCode,
            @PathVariable("itemCode") String itemCode,
            @RequestBody UpsertItemRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);

        DictionaryPort.DictionaryItemRow current = requireItem(dictCode, itemCode);

        String newCode = trimToNull(request.itemCode());
        boolean wantsRename = newCode != null && !newCode.equals(itemCode);
        if (wantsRename && current.builtIn()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "内置字典项的取值编码不可修改（后端按枚举校验的正是它）；可改中文标签或停用该项");
        }
        if (wantsRename && !ITEM_CODE.matcher(newCode).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "取值编码只允许字母、数字、点、下划线与短横线");
        }

        String label = trimToNull(request.itemLabel());
        if (label == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "展示标签不能为空");
        }

        String targetCode = wantsRename ? newCode : itemCode;
        dictionaries.upsertItem(new DictionaryPort.DictionaryItemRow(
                dictCode,
                targetCode,
                label,
                request.sortOrder() == null ? current.sortOrder() : request.sortOrder(),
                current.builtIn(),
                request.enabled() == null ? current.enabled() : request.enabled()));

        if (wantsRename) {
            // 改名语义 = 新建目标编码 + 停用原编码（绝不物理删除原行）
            dictionaries.setItemEnabled(dictCode, itemCode, false);
        }
        return ItemView.from(requireItem(dictCode, targetCode));
    }

    /**
     * 停用一项（<b>不是物理删除</b>）。
     *
     * <p>⚠️ 内置项不可停用？——不，内置项<b>可以停用</b>（例如某个 HTTP 方法不再允许），
     * 但<b>不可以删除</b>。删除会让历史行的取值变成悬空引用，而停用仍保留可解释性。
     * 因此这里对内置项同样只翻 {@code enabled} 位，不删行。
     */
    @DeleteMapping("/{dictCode}/items/{itemCode}")
    public ItemView disableItem(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable("dictCode") String dictCode,
            @PathVariable("itemCode") String itemCode) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);

        DictionaryPort.DictionaryItemRow current = requireItem(dictCode, itemCode);
        dictionaries.setItemEnabled(dictCode, itemCode, false);
        return ItemView.from(requireItem(dictCode, current.itemCode()));
    }

    /* ── 内部 ─────────────────────────────────────────────── */

    private DictionaryPort.DictionaryRow requireDictionary(String dictCode) {
        return dictionaries.findDictionary(dictCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "字典不存在：" + dictCode));
    }

    private DictionaryPort.DictionaryItemRow requireItem(String dictCode, String itemCode) {
        return dictionaries.findItem(dictCode, itemCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "字典项不存在：" + dictCode + "/" + itemCode));
    }

    private DictionaryView toView(DictionaryPort.DictionaryRow row, boolean enabledOnly) {
        List<ItemView> items = dictionaries.listItems(row.dictCode()).stream()
                .filter(i -> !enabledOnly || i.enabled())
                .map(ItemView::from)
                .toList();
        return new DictionaryView(
                row.dictCode(),
                row.dictName(),
                row.description(),
                row.builtIn(),
                row.enabled(),
                row.sortOrder(),
                items);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 字典 + 其取值。 */
    public record DictionaryView(
            String dictCode,
            String dictName,
            String description,
            boolean builtIn,
            boolean enabled,
            int sortOrder,
            List<ItemView> items) {
    }

    /** 字典项。 */
    public record ItemView(
            String dictCode,
            String itemCode,
            String itemLabel,
            int sortOrder,
            boolean builtIn,
            boolean enabled) {

        static ItemView from(DictionaryPort.DictionaryItemRow row) {
            return new ItemView(
                    row.dictCode(),
                    row.itemCode(),
                    row.itemLabel(),
                    row.sortOrder(),
                    row.builtIn(),
                    row.enabled());
        }
    }

    public record CreateDictionaryRequest(
            String dictCode,
            String dictName,
            String description,
            Integer sortOrder) {
    }

    public record UpdateDictionaryRequest(
            String dictName,
            String description,
            Boolean enabled,
            Integer sortOrder) {
    }

    public record UpsertItemRequest(
            String itemCode,
            String itemLabel,
            Integer sortOrder,
            Boolean enabled) {
    }
}
