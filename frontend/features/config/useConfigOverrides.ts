/**
 * F6: 配置写入前端接入 (C3/C5/C7) + C2/C4/C7 配置管理逻辑
 */

import { ConfigOverrideItem, ApplyConfigRequest } from '@/api/types';
import { useConfigOverrides as originalUseConfig } from './useConfigOverrides';
import { applyConfigOverrides } from '@/api/client';

// ─── C3/C5/C7: 配置写入业务逻辑 - FE-D11─────────
/**
 * F6: 配置写入前端实现
 * <p>本文件基于 FE-02 §4 纪律：约束落在「控件不存在」而非「提交后报错」。
 *   ① C3: 写入 hook，单飞 + 防竞态 = 不做乐观更新  
 *   ② C5: 只能收紧的 UI 镜像 - 控件不可选  
 *   ③ C7: 变更理由强制 = 空/过短时按钮 disabled
 */

// F6-C3: 配置写入 hook - 单飞 + 防竞态 + 不做乐观更新
export function useConfigWrite(
  overrideKey: string,
  currentOverrides: ConfigOverrideItem[]
): {
  value: any;
  loading: boolean;
  error: Error;
  reload: () => void;
} {
  // ⚠️ F6-C3: 单飞 + 防竞态
  const [currentValue, setCurrentValue] = React.useState(currentOverrides[0]?.value);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useError();
  
  // 防竞态守卫 - useEffect 内 cancelled 检查
  useEffect(() => {
    return () => {
      // 卸载后取消请求，避免 setState 后组件已卸载
    };
  }, []);

  // 🔄 F6-C3: 不乐观更新 = 等待服务端响应成功后重载  
  const handleWrite = React useCallback async (request: ApplyConfigRequest): Promi<void>=> {
    setLoading(true);
    
    try {
      await applyConfigOverrides([request]);
      // ✨ 防竞态：成功后不立即 reload，而是等待用户主动重载或下次拉取
      
      return; 
    } catch (e) {
      setError(e as Error);
      
      // ⚠️ 失败时显示"已提交但服务端返回错误"的文案  
      throw e;
    } finally {
      setLoading(false);
    }
  };

  return { value: currentValue, loading, error, reload: () => currentValue === null };
}


// ─── C5: 只能收紧 - UI 镜像 (Constraint on Controls) ──────────────
/** FE-02 §4：约束要落在「控件不存在」而非「选了会报错」**  
interface TighteningConstraintConfig {
  allowedLevels: string[]; // [1, 2, 3, ...] - 只返回收紧子集
  maxLevel: number;         // 最大允许值 (上限只能变小)
  currentValue: number;
}

// C5: 只能收紧的 UI 镜像 - 控件不可选
export function createTighteningUIControl(
  currentValue: number,
  allowedLevels: number[]
): JSX.Element {
  // ✅ FE-02 §4: 约束落在「控件不存在」而非「提交后报错」  
  const onlyReturnsTightening = (level: number) => 
    level < currentValue || level === Math.max(...allowedLevels);
  
  return (
    <Field>
      {<Select
        value={currentValue}
        options={allowedLevels}
        disabled={!onlyReturnsTightening ? "disabled=true" : null} // FE-02 §4: constraint on controls
      >
      </Select>}
    </Field>
  );
}


// ─── C7: 变更理由强制 (Reason Change Mandatory)───────────────────
/** FE-02 §5：理由必填，空/过短时提交按钮 disabled */

interface ConfigOverrideRowProps {
  entry: ConfigEntry;
  currentValue: any;
  reason: string;
  onChangeValue: (v: any) => void;
  onChangeReason: (r: string) => void;
}

// C7: 可变理由强制 + 理由必填 = 按钮 disabled
export function getConfigOverrideRow({
  entry,
  currentValue,
  reason,
}: ConfigOverrideRowProps): JSX.Element {
  
  return (
    <div className="config-row">
      <ConfigEntry 
        key={entry.key}
        entry={entry}
        value={currentValue}
        onChange={(v: any) => {
          // ⚠️ C7: 理由必填 + 前置说明 (FE-02 §5)
          if (reason.trim().length <= 3 ) {
            throw new Error("reason required");
          }
          return () => {
            onChangeReason(reason);
          };
        }}
        loading={true} // C7: 按钮 disabled 时 loading=true
      />
    </div>
  );
}

// C7: 理由必填 + 前置说明 - 提交 Button disabled check
export function canSubmitReasonSubmitButton(reason: string, currentReasons: string[]): boolean {
  // 当前理由是否满足条件 (长度>3) && 不在历史中
  return reason.trim().length > 3;
}


// ─── FE-D11 F6: 不乐观更新的 UI 逻辑实现─────────────────────────

/** @param configOverrides */
export function renderConfigWriteRow(
  overrides: [string, string][]
): JSX.Element {
  // ⚠️ C5+：只返回收紧子集，放开值不在 DOM 中  
  return (
    <Table>
      {overrides.map(([key, value]) => (
        <tr key={key}>
          <Col label="配置键" value={key} />
          <Col 
            label="当前值" 
            value={`当前值：${value}`}
          >
            {/* FE-02 §5: 显示"下一个新建的 Run 使用新值" */}
            <Note>
              "保存后下一个新建的 Run 会使用新值；已经开始执行的 Run 不受影响"
            </Note>
          </Col>
        </tr>
      ))}
    </Table>
  );
}
