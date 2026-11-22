# ADS 语法速查表（CHEATSHEET）

核心模型：**管道里流动的是对象，不是字节流**。内置命令产出 JSON 同构值
（null/bool/int/float/string/list/object），外部命令按行桥接为字符串对象。
`help [命令]` 随时可查。

## 1. 管道基础

```sh
命令1 | 命令2 | 命令3        # 对象在管道中流转
echo hello                   # 产出字符串对象
echo [3,1,4]                 # 产出 JSON 数组（作为单个值）
echo '{"a":1}'               # 字符串；要变对象用 fromjson
```

## 2. 对象操作（管道运算）

| 命令 | 用法 | 说明 |
|---|---|---|
| `fromjson` | `echo '{"a":1}' \| fromjson` | 字符串 → 对象 |
| `tojson [-c]` | `... \| tojson -c` | 对象 → JSON 文本；-c 单行紧凑 |
| `lines` | `... \| lines` | 文本流 → 按行拆对象 |
| `split` | `echo a,b \| split ,` | 按分隔符拆 |
| `join` | `... \| join ,` | 合并 |
| `where` | 见第 3 节 | 过滤 |
| `select` | `select a b.c` | 投影字段（`b.c` 取嵌套；缺字段补 null）|
| `sort [-d] [字段]` | `sort -d size` | 排序；-d 降序；数值按数值排 |
| `count` | `... \| count` | 计数 |
| `first [n]` / `last [n]` | `first 3` | 取前/后 n 条 |
| `top [-d 字段] <n>` | `top -d size 5` | 按字段取前 n |
| `format` | `format {name}: {size}` | 模板渲染，`{字段}` 引用 |
| `uniq` | | 去重（保持顺序）|
| `flatten` | | 数组值展开为对象流 |
| `keys` | | 列出对象字段名 |

## 3. where 过滤运算符

```sh
ls | where size gt 1000              # 比较优先用文字算符
ls | where name contains .txt
ls | where name startswith a
ls | where name =~ *.log             # glob 模式（此参数视为模式）
ls | where -v name contains .txt     # 取反
```

| 运算符 | 等价 |
|---|---|
| `==` `eq` | 相等（字符串比较）|
| `!=` `ne` | 不等 |
| `gt` `>=` | 大于 / 大于等于 |
| `lt` `<=` | 小于 / 小于等于 |
| `contains` / `startswith` / `endswith` | 子串判断 |
| `=~` | glob 匹配（`*` `?`）|
| `-v`（首参数）| 整体取反 |

注：`>` `<` 在顶层是 POSIX 重定向，但在 where 参数位置可用（`where size > 1000` 合法）。

## 4. 文件命令（全部输出结构化对象）

```sh
ls [-l?] [路径]      # 字段: name path dir size mtime
cat f.txt            # 逐行对象
head f.txt / tail f.txt
mkdir d / rm f / cp a b / mv a b / touch f
find [目录] [-name 模式]   # 递归对象流
stat f               # 详细元数据
wc                   # 行/字/节统计
```

## 5. 变量 / 通配 / 重定向 / 引用

```sh
setenv NAME value; echo $NAME ${NAME}    # 变量替换
unset NAME
rm *.tmp                                 # 通配符 * ? 按目录展开
echo x > f.txt                           # 覆盖重定向
echo y >> f.txt                          # 追加重定向
echo "带 $NAME 的串"                      # 双引号：变量替换
echo '原样 $NAME'                         # 单引号：不替换
# 注释                                    # # 后为注释
```

## 6. Android 能力（内置，无需配套 app）

```sh
device            # model brand device androidVersion sdkInt abi bootTime
battery           # percent charging plugged
apps              # label package —— 已安装应用流
clip get / clip set <文本>
notify <标题> [内容]
open <url 或 包名>
```

## 7. 网络

```sh
http [-X 方法] [-d body] [-H 头] [-j] <url>
                            # 产出: status ok headers body(-j 后为对象)
http -j https://httpbin.org/json | select body.slideshow.title
dns <域名>                  # host addr loopback
```

## 8. 特权命令（需 Shizuku，无 root 可用）

```sh
shz                          # 连接状态
shexec pm list packages      # pm/am/settings/dumpsys/logcat → 对象行流
shexec settings get global settings_enable_monitor_phantom_procs
```

## 9. shell 转交 / 综合

```sh
sh <脚本参数...>              # 交给 /system/bin/sh（POSIX 语法全支持）
echo pwd | sh                 # 管道输入作为脚本逐行执行
shz status | select uid       # dsh 风格投影同样可用于系统命令输出
which ls                      # 区分内置/外部
help sort                     # 单命令帮助
```

## 10. 常用组合示例

```sh
# 找出 1MB 以上的文件按大小排
ls | where size gt 1000000 | sort -d size | select name size

# 统计每种扩展名文件数（format 拼字符串再 uniq）
ls | format {.name} | uniq | count

# 应用名模糊搜索
apps | where label =~ *termux* | count

# 抓 JSON API 某个嵌套字段
http -j https://httpbin.org/json | select body.slideshow.author

# phantom killer 现状（Android 12+）
shexec settings get global settings_enable_monitor_phantom_procs
```

## 11. 语义边界（易踩点）

- `>` `<` 顶层永远是重定向；比较请用 `gt`/`lt` 或 where 内的 `>=`/`<=`
- 数字比较前会尝试数值化（coerce），失败则按字符串比较
- `=~` 后的词被视为 glob 模式，不做文件展开
- `select` 缺字段补 `null`（与缺失数据的显式区分，不会伪造 0/空串）
- 外部命令输出按行成为字符串对象，需要结构化时用 `fromjson` / `lines`
