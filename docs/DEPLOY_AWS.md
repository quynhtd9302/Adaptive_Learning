# Deploy Adaptive Learning lên AWS

Hạ tầng: **VPC → Subnet → Security Group → ALB → ECS Fargate → RDS → S3 → ElastiCache Redis**
Region **ap-southeast-2 (Sydney)** · Account `482762106946` · VPC `vpc-adaptive-learning`

> **Trên AWS không dùng EC2 và không dùng docker-compose.**
> - Container chạy trên **ECS Fargate** (serverless — AWS tự lo máy chủ, không có máy EC2 nào để SSH hay quản lý).
> - Database là **RDS**, cache là **ElastiCache**, file là **S3** — đều là dịch vụ managed, không chạy trong container.
> - Mỗi container được khai báo bằng **ECS Task Definition** (`deploy/ecs/*.json`) thay cho docker-compose.
> - `docker-compose.yml` trong repo **chỉ để chạy thử trên máy local**.

```
                         Internet
                            │ HTTP 80 (sau này HTTPS 443)
┌── vpc-adaptive-learning ──▼─────────────────────────────────────────────────┐
│  PUBLIC  2a: Public-Subnet-adaptive-1   2b: Public-Subnet-adaptive-4         │
│     ┌──────────────── ALB  [adaptive-learning-scg] ────────────────┐         │
│     │  /api/*  /uploads/*  /actuator/*  → tg-adaptive-backend :8080 │         │
│     │  còn lại                          → tg-adaptive-frontend :80  │         │
│     └────────────┬───────────────────────────────┬─────────────────┘         │
│          ECS service frontend            ECS service backend ─────────► S3   │
│          (nginx + React)                 (Spring Boot)   [adaptive-learning-app-sg]
│  ───────────────────────────────────────────┼──────────┼────────────────────  │
│  PRIVATE 2a: Private-Subnet-adaptive-2   2b: Private-Subnet-adaptive-3        │
│                                     RDS MySQL      ElastiCache Redis          │
│                                     [db-sg]        [redis-sg]                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

Làm theo **4 giai đoạn**; hết mỗi giai đoạn app đều chạy được để kiểm tra:

| Giai đoạn | Thêm | Cấu hình backend |
|---|---|---|
| 1 | VPC, SG, RDS, ECR, ECS, ALB | `STORAGE_TYPE=local`, `CACHE_TYPE=simple`, backend **1 task** |
| 2 | S3 | `STORAGE_TYPE=s3`, `S3_BUCKET=...` |
| 3 | ElastiCache Redis | `CACHE_TYPE=redis`, `REDIS_HOST=...`, `REDIS_HEALTH=true` → tăng lên **2 task** |
| 4 | Tên miền + HTTPS | Bắt buộc để **camera theo dõi tập trung** chạy được |

> Vì sao giai đoạn 1 chỉ 1 task backend? File upload còn nằm trên ổ đĩa của task và cache nằm trong RAM của từng task.
> Chạy 2 task thì task A không thấy file/cache của task B. **S3 và Redis giải quyết đúng 2 vấn đề đó** — đây là lý do kiến trúc cần chúng.

---

## 0. Chuẩn bị trên máy Windows

1. **Docker Desktop** đang chạy.
2. **AWS CLI v2** (bản MSI cho Windows) → mở PowerShell mới: `aws --version`.
3. **IAM user cho CLI** (không dùng root): IAM → Users → Create user `quynh-cli` → Attach policy `AdministratorAccess` (chỉ khi học) → vào user → *Security credentials* → **Create access key** (Command Line Interface).
4. ```powershell
   aws configure            # region: ap-southeast-2, output: json
   aws sts get-caller-identity
   ```
5. *(Tùy chọn, chỉ trên máy local)* chạy thử: `docker compose up -d --build` → `http://localhost:3000`. Bước này không liên quan tới AWS.

---

## 1. Security Group (VPC → Security Groups, chọn VPC `vpc-adaptive-learning`)

| SG | Inbound | Source |
|---|---|---|
| `adaptive-learning-scg` (ALB) | HTTP 80 · *(giai đoạn 4: HTTPS 443)* | `0.0.0.0/0` |
| `adaptive-learning-app-sg` (ECS) | HTTP 80 · Custom TCP 8080 | `adaptive-learning-scg` |
| `adaptive-learning-db-sg` (RDS) | MySQL/Aurora 3306 | `adaptive-learning-app-sg` |
| `adaptive-learning-redis-sg` (Redis) | Custom TCP 6379 | `adaptive-learning-app-sg` |

Outbound tất cả: mặc định **All traffic → 0.0.0.0/0** (ECS cần ra ngoài để kéo image, gọi S3, ghi log).

---

## 2. RDS MySQL

RDS `adaptive-db`: MySQL 8.0, private subnet group (Private-2 + Private-3), SG `adaptive-learning-db-sg`, **Public access = No**.

- **Không cần import SQL, không cần máy trung gian**: khi backend khởi động lần đầu, `createDatabaseIfNotExist=true` tạo database `adaptive_learning_db`, rồi **Flyway** chạy `V1__schema.sql` (bảng) và `V2__seed_data.sql` (dữ liệu mẫu).
- Copy **Endpoint** ở tab *Connectivity & security*.

**Lưu mật khẩu DB:** Systems Manager → Parameter Store → Create parameter
- Name `/adaptive-learning/db-password` · Type **SecureString** · Value = mật khẩu master RDS.

---

## 3. Đẩy image lên ECR

```powershell
cd D:\source_code\Adaptive_Learning
.\deploy\push-ecr.ps1
```
Script tự tạo repository `adaptive-backend`, `adaptive-frontend`, build `linux/amd64`, push tag `latest`.
(PowerShell chặn script: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.)

---

## 4. IAM Role

**4.1 `ecsTaskExecutionRole`** — ECS dùng để kéo image, ghi log, đọc mật khẩu
- Chưa có: IAM → Roles → Create role → AWS service → **Elastic Container Service → Elastic Container Service Task** → policy `AmazonECSTaskExecutionRolePolicy` → tên `ecsTaskExecutionRole`.
- Thêm inline policy (JSON): `deploy/iam/execution-role-extra-policy.json`.

**4.2 `adaptive-learning-task-role`** — quyền của **code Java** (gọi S3)
- Create role → **Elastic Container Service Task** → chưa gắn policy (giai đoạn 2 mới thêm) → tên `adaptive-learning-task-role`.

---

## 5. ECS Cluster + Task Definition

1. ECS → Clusters → Create → `adaptive-cluster` → **AWS Fargate**.
2. ECS → Task definitions → **Create new task definition with JSON**:
   - `deploy/ecs/backend-task-def.json` → thay `<RDS_ENDPOINT>`
   - `deploy/ecs/frontend-task-def.json`

Biến môi trường backend (giai đoạn 1):

| Biến | Giá trị |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (dùng `application-prod.yml`) |
| `DB_URL` | `jdbc:mysql://<RDS_ENDPOINT>:3306/adaptive_learning_db?createDatabaseIfNotExist=true&...` |
| `DB_USERNAME` | `admin` |
| `DB_PASSWORD` | *(secrets → SSM `/adaptive-learning/db-password`)* |
| `APP_UPLOAD_BASE_URL` | `/uploads` |
| `STORAGE_TYPE` | `local` |
| `CACHE_TYPE` | `simple` |
| `REDIS_HEALTH` | `false` |

---

## 6. Target Group + Application Load Balancer

**6.1 Target group** (Console → mục **EC2** → *Load Balancing* → Target Groups → Create — Target Group và ALB nằm trong menu EC2 của console nhưng **không tạo máy EC2 nào**; *Target type = IP addresses*, VPC `vpc-adaptive-learning`, không register target):

| | `tg-adaptive-frontend` | `tg-adaptive-backend` |
|---|---|---|
| Protocol / Port | HTTP / 80 | HTTP / 8080 |
| Health check path | `/healthz` | `/actuator/health` |
| Healthy threshold / Interval | 2 / 15s | 2 / 15s |

Sau khi tạo: tab *Attributes* → **Deregistration delay = 30s**.

**6.2 ALB** (Console → mục **EC2** → *Load Balancing* → Load Balancers → Create → Application Load Balancer)
- `alb-adaptive` · Internet-facing · VPC `vpc-adaptive-learning`
- Mappings: **2a → Public-Subnet-adaptive-1**, **2b → Public-Subnet-adaptive-4**
- Security group: **chỉ** `adaptive-learning-scg`
- Listener HTTP:80 → default forward **`tg-adaptive-frontend`**
- Sau khi tạo: tab *Attributes* → **Connection idle timeout = 300** giây (upload video lớn tới 500MB).

**6.3 Rule** (Listeners and rules → HTTP:80 → Add rule)
- Condition **Path**: `/api/*`, `/uploads/*`, `/actuator/*` → Forward **`tg-adaptive-backend`** · Priority `10`.

---

## 7. ECS Service (Cluster `adaptive-cluster` → Services → Create)

| Mục | backend | frontend |
|---|---|---|
| Launch type | FARGATE | FARGATE |
| Task definition | `adaptive-backend` | `adaptive-frontend` |
| Desired tasks | **1** (giai đoạn 3 tăng lên 2) | 1 |
| Subnets | Public-Subnet-adaptive-1 + Public-Subnet-adaptive-4 | như backend |
| Security group | **chỉ** `adaptive-learning-app-sg` | như backend |
| Public IP | **ON** (kéo image từ ECR, không cần NAT Gateway) | ON |
| Load balancer | `alb-adaptive`, listener HTTP:80, TG `tg-adaptive-backend`, container `backend 8080` | TG `tg-adaptive-frontend`, container `frontend 80` |
| Health check grace period | **180** giây (lần đầu Flyway nạp dữ liệu mẫu) | 30 |

---

## 8. Kiểm tra giai đoạn 1

Lấy **DNS name** của ALB rồi mở:
- `http://<DNS>/actuator/health` → `"status":"UP"`, `db: UP`
- `http://<DNS>/api/courses` → JSON danh sách khóa học
- `http://<DNS>/` → đăng nhập `admin@example.com` / `admin123`

Log: ECS → service → task → tab **Logs** (lần đầu sẽ thấy Flyway `Migrating schema ... to version "1"`, `"2"`).

---

## 9. Giai đoạn 2 — S3

1. S3 → Create bucket `adaptive-learning-uploads-<số-ngẫu-nhiên>` · region ap-southeast-2 · **giữ Block all public access**.
2. IAM → `adaptive-learning-task-role` → Create inline policy → JSON `deploy/iam/task-role-s3-policy.json` (thay `<BUCKET_NAME>`).
3. Task definition `adaptive-backend` → **Create new revision** → sửa biến:
   `STORAGE_TYPE = s3`, thêm `S3_BUCKET = adaptive-learning-uploads-xxxx`.
4. Service backend → **Update** → revision mới → **Force new deployment**.
5. Thử: admin upload ảnh khóa học / video bài học → file nằm trong bucket (`images/`, `videos/`).

Cách hoạt động: DB vẫn lưu `/uploads/videos/abc.mp4`. Trình duyệt gọi đường dẫn đó → ALB → backend trả **302** sang presigned URL của S3 → video tải thẳng từ S3, backend không phải gửi file nặng.

---

## 10. Giai đoạn 3 — ElastiCache Redis

1. ElastiCache → Subnet groups → Create `adaptive-redis-subnets` (Private-2 + Private-3).
2. Create cache → **Valkey** (tương thích Redis) hoặc Redis OSS → *Design your own cache* → **Cluster cache** (không chọn Serverless)
   → Cluster mode Disabled · `cache.t3.micro` · 0 replica · subnet group trên · SG **chỉ** `adaptive-learning-redis-sg`.
3. Copy **Primary endpoint** (bỏ `:6379`).
4. Task definition backend → new revision: `CACHE_TYPE=redis`, `REDIS_HEALTH=true`, thêm `REDIS_HOST=<endpoint>`, `REDIS_SSL=true` nếu bật *Encryption in transit*.
5. Update service backend → revision mới → Force new deployment → **Desired tasks = 2**.
6. Kiểm tra `/actuator/health` có `redis: UP`. API được cache: `/api/courses`, `/api/courses/trending`, `/api/courses/{id}` (tự xóa cache khi admin thêm/sửa/xóa khóa học).

---

## 11. Giai đoạn 4 — HTTPS (camera cần HTTPS)

Trình duyệt chỉ cho mở webcam trên **HTTPS**.
1. Có tên miền (Route 53 hoặc nhà cung cấp khác).
2. **ACM** (Certificate Manager, region ap-southeast-2) → Request public certificate → xác thực DNS.
3. ALB → Add listener **HTTPS:443** → certificate vừa tạo → default forward `tg-adaptive-frontend` + rule giống mục 6.3.
4. Listener HTTP:80 → đổi default action thành **Redirect to HTTPS 443**.
5. `adaptive-learning-scg` thêm inbound HTTPS 443 từ `0.0.0.0/0`.
6. Trỏ tên miền (CNAME/Alias) về DNS name của ALB.

---

## 12. Cập nhật code

```powershell
.\deploy\push-ecr.ps1
```
→ ECS → service → **Update service → Force new deployment**.
Đổi cấu trúc DB: **không sửa** V1/V2, tạo file mới `V3__mo_ta.sql` trong `db/migration`.

---

## 13. Lỗi thường gặp

| Hiện tượng | Nguyên nhân |
|---|---|
| `CannotPullContainerError` | Service chưa bật Public IP / subnet không có route ra IGW / execution role thiếu policy |
| `ResourceInitializationError ... ssm` | Execution role thiếu inline policy, hoặc sai tên parameter |
| Target unhealthy, web 502/503 | `adaptive-learning-app-sg` chưa mở 80/8080 từ SG ALB; backend chưa khởi động xong (tăng grace period) |
| `Communications link failure` | `adaptive-learning-db-sg` chưa cho 3306 từ app-sg, hoặc sai endpoint |
| `Access denied for user 'admin'` | Sai mật khẩu trong SSM |
| Health DOWN vì redis | Chưa có Redis mà `REDIS_HEALTH=true`, hoặc sai `REDIS_SSL` |
| Upload lỗi 502 "Could not upload file to S3" | Sai `S3_BUCKET` hoặc task role chưa có policy S3 |
| Upload video lớn bị ngắt | Chưa tăng ALB idle timeout lên 300s |
| `/api/...` trả về trang HTML | Thiếu rule path trên listener ALB |
| Camera báo cần HTTPS | Làm giai đoạn 4 |

---

## 14. Dọn dẹp — tránh mất tiền

- Nghỉ ngắn: mỗi ECS service → **Desired tasks = 0**; RDS → **Stop temporarily** (tự bật lại sau 7 ngày).
- ElastiCache không có Stop → xóa cluster. ALB tính tiền theo giờ kể cả không có traffic.
- Xóa hẳn: ECS services → cluster → ALB → target groups → ElastiCache → RDS → S3 (xóa object trước) → ECR → CloudWatch log groups.
- Đặt **AWS Budgets** cảnh báo 5–10 USD/tháng.
