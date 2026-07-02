<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **TYMAP** (1881 symbols, 3158 relationships, 94 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "master"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/TYMAP/context` | Codebase overview, check index freshness |
| `gitnexus://repo/TYMAP/clusters` | All functional areas |
| `gitnexus://repo/TYMAP/processes` | All execution flows |
| `gitnexus://repo/TYMAP/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

# Quy tắc Dự án (TYMAP Project Rules)

## Tối ưu hóa hiệu năng & Trải nghiệm dẫn đường GPS

- **Quy tắc GPS & Threading:**
  - Không chạy các tác vụ tính toán nặng (như giải mã polyline, tìm khoảng cách lệch tuyến) trên Main Thread. Luôn sử dụng `Dispatchers.Default`.
  - Tần suất chạy thuật toán off-route detection phải giới hạn tối thiểu 2 giây một lần để tránh làm lag Main Thread.
- **Quy tắc Marker & Camera:**
  - Không được hủy và tạo lại đối tượng `userMarker` trong callback cập nhật vị trí. Chỉ cập nhật bằng `marker.setPosition()` và `marker.setRotation()`.
  - Xoay marker phải sử dụng `ValueAnimator` nội suy góc quay mượt mà (300ms) để tránh giật con trỏ chỉ hướng.
  - Camera map di chuyển theo xe phải dùng `mapView.controller.animateTo()` thay vì `setCenter()`.
- **Quy tắc Polyline:**
  - Áp dụng thuật toán Douglas-Peucker để đơn giản hóa số điểm polyline vẽ lên bản đồ với ngưỡng sai số 3 pixel ở mức zoom hiện tại.
  - Xóa sạch overlays polyline cũ trước khi thêm polyline mới. Bật chống răng cưa (`isAntiAlias = true`) cho đường vẽ.
  - Gom các lần vẽ lại bản đồ bằng `postInvalidateDelayed(50)`.
- **Quy tắc Thiết lập & Dịch vụ:**
  - Xin quyền `ACCESS_BACKGROUND_LOCATION` và cấu hình Foreground Service có type `location` để không bị mất định vị khi khóa màn hình.
  - Sử dụng cấu hình GPS `minDistanceMeters = 2f` và `Priority.PRIORITY_HIGH_ACCURACY`.
  - Tự động fallback sang bản đồ nền mặc định `CartoDB Positron` khi tải tile vệ tinh ESRI bị lỗi.
  - Định tuyến trên GraphHopper sử dụng profile `motorcycle` khi chọn phương tiện Xe máy; các engine khác phải tích hợp tham số tránh đường cao tốc (highways/motorways).