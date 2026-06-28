# GitHub Copilot Custom Instructions

## Skill: Codebase to Overview

Khi người dùng yêu cầu "tạo tài liệu tổng quan", "overview dự án", "mô tả project", hoặc "phân tích dự án", hãy thực hiện skill `codebase-to-overview`:

1. Xác định thư mục gốc của dự án.
2. Duyệt toàn bộ cây thư mục, đọc nội dung các file mã nguồn.
3. Phân tích và tạo file `{TÊN_DỰ_ÁN}_OVERVIEW.md`.
4. Lưu file vào thư mục gốc của dự án.

Xem chi tiết tại `.agents/skills/codebase-to-overview/SKILL.md` hoặc `codebase-to-overview.prompt.md`.
