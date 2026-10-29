# Deploy Adaptive Learning lên AWS

Hạ tầng: **VPC (Public / Private / Data subnet) → NAT → Security Group → ALB → ECS Fargate → RDS → S3 → ElastiCache Redis**
Region **ap-southeast-2 (Sydney)** · Account `482762106946` · VPC `vpc-adaptive-learning`

> **Trên AWS không dùng EC2 và không dùng docker-compose.**
> - Container chạy trên **ECS Fargate** (serverless — AWS tự lo máy chủ, không có máy EC2 nào để SSH hay quản lý).
> - Database là **RDS**, cache là **ElastiCache**, file là **S3** — đều là dịch vụ managed, không chạy trong container.
> - Mỗi container được khai báo bằng **ECS Task Definition** (`deploy/ecs/*.json`) thay cho docker-compose.
> - `docker-compose.yml` trong repo **chỉ để chạy thử trên máy local**.

VPC chia **3 tầng subnet**, mỗi tầng 2 AZ (2a, 2b):

| Tầng | Chứa | Ra internet |
|---|---|---|
| **Public** | ALB, NAT Gateway | Trực tiếp qua Internet Gateway |
| **Private (app)** | ECS Fargate task (frontend, backend) | Chỉ chiều đi ra, qua NAT Gateway |
| **Data** | RDS MySQL, ElastiCache Redis | **Không** (chỉ nói chuyện trong VPC) |

```
                              Internet
                                 │ HTTP 80 (sau này HTTPS 443)
┌── vpc-adaptive-learning 10.0.0.0/16 ─────▼─────────────────────────────────────┐
│ PUBLIC   adaptive-public-2a 10.0.1.0/24     adaptive-public-2b 10.0.2.0/24     │
│   ┌──────────── ALB [adaptive-learning-scg] ─────────────┐     NAT Gateway (2a) │
│   │ /api/* /uploads/* /actuator/* → tg-adaptive-backend  │          ▲           │
│   │ còn lại                       → tg-adaptive-frontend │          │ ra ngoài: │
│   └──────────┬───────────────────────────┬───────────────┘          │ ECR, logs,│
│ ─────────────┼───────────────────────────┼──────────────────────────┼─ SSM ──── │
│ PRIVATE  adaptive-app-2a 10.0.11.0/24    adaptive-app-2b 10.0.12.0/24          │
│        ECS frontend :80           ECS backend :8080  ──── S3 Gateway Endpoint ──► S3
│        (nginx + React)            (Spring Boot)   [adaptive-learning-app-sg]   │
│ ─────────────────────────────────────────┼────────────┼─────────────────────── │
│ DATA     adaptive-data-2a 10.0.21.0/24   adaptive-data-2b 10.0.22.0/24         │
│                                    RDS MySQL     ElastiCache Redis             │
│                                    [db-sg]       [redis-sg]                    │
└────────────────────────────────────────────────────────────────────────────────┘
```

Làm theo **4 giai đoạn**; hết mỗi giai đoạn app đều chạy được để kiểm tra:

| Giai đoạn | Thêm | Cấu hình backend |
|---|---|---|
| 1 | VPC (3 tầng subnet + NAT), SG, RDS, ECR, ECS, ALB | `STORAGE_TYPE=local`, `CACHE_TYPE=simple`, backend **1 task** |
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

## 1. VPC, Subnet, Route table, NAT

> Tên và CIDR dưới đây là gợi ý. Nếu đã tạo VPC với tên khác thì giữ tên của bạn, miễn đúng **tầng** và **route**.

**1.1 VPC**: VPC → Create VPC → *VPC only* → `vpc-adaptive-learning`, CIDR `10.0.0.0/16`.
Sau khi tạo: *Actions → Edit VPC settings* → bật **DNS resolution** và **DNS hostnames** (bắt buộc để dùng endpoint RDS, Redis, VPC endpoint).

**1.2 Subnet** (VPC → Subnets → Create, chọn VPC trên):

| Subnet | AZ | CIDR | Tầng |
|---|---|---|---|
| `adaptive-public-2a` | ap-southeast-2a | 10.0.1.0/24 | Public |
| `adaptive-public-2b` | ap-southeast-2b | 10.0.2.0/24 | Public |
| `adaptive-app-2a` | ap-southeast-2a | 10.0.11.0/24 | Private (app) |
| `adaptive-app-2b` | ap-southeast-2b | 10.0.12.0/24 | Private (app) |
| `adaptive-data-2a` | ap-southeast-2a | 10.0.21.0/24 | Data |
| `adaptive-data-2b` | ap-southeast-2b | 10.0.22.0/24 | Data |

**1.3 Internet Gateway**: Create `igw-adaptive` → *Attach to VPC* `vpc-adaptive-learning`.

**1.4 NAT Gateway**: VPC → NAT gateways → Create
- `nat-adaptive` · Subnet **`adaptive-public-2a`** (NAT phải nằm ở subnet public) · Connectivity **Public** · *Allocate Elastic IP*.
- Chỉ tạo 1 NAT cho rẻ. Production thật thì mỗi AZ một NAT, mỗi app subnet trỏ về NAT cùng AZ.

**1.5 Route table** (VPC → Route tables → Create, rồi tab *Subnet associations* gán subnet):

| Route table | Routes (ngoài route `local` mặc định) | Gán subnet |
|---|---|---|
| `rt-adaptive-public` | `0.0.0.0/0` → `igw-adaptive` | public-2a, public-2b |
| `rt-adaptive-app` | `0.0.0.0/0` → `nat-adaptive` | app-2a, app-2b |
| `rt-adaptive-data` | *(không thêm gì, chỉ `local`)* | data-2a, data-2b |

> Tầng data **không có route ra internet**, kể cả qua NAT. RDS và Redis chỉ được ECS trong VPC gọi tới.

**1.6 S3 Gateway Endpoint** (miễn phí, nên làm): VPC → Endpoints → Create
- Service `com.amazonaws.ap-southeast-2.s3` · Type **Gateway** · VPC `vpc-adaptive-learning` · Route table **`rt-adaptive-app`**.
- Traffic tới S3 (cả layer image ECR, vốn lưu trên S3) đi thẳng, không qua NAT, nên tiết kiệm phí xử lý dữ liệu của NAT.

> **Vì sao task ECS ở private vẫn cần NAT?** Fargate phải gọi ra các API công khai của AWS: ECR (kéo image), CloudWatch Logs (ghi log), SSM (đọc mật khẩu DB). Không có NAT thì task báo `CannotPullContainerError` / `ResourceInitializationError`.
> Cách thay NAT: tạo **Interface Endpoint** cho `ecr.api`, `ecr.dkr`, `logs`, `ssm` (bật Private DNS, đặt ở 2 app subnet, SG cho 443 từ app-sg). Cách này không cần internet, nhưng 4 endpoint × 2 AZ thường **đắt hơn** 1 NAT, nên khi học thì dùng NAT.

---

## 2. Security Group (VPC → Security Groups, chọn VPC `vpc-adaptive-learning`)

**2.1 Inbound**

| SG | Inbound | Source |
|---|---|---|
| `adaptive-learning-scg` (ALB) | HTTP 80 · *(giai đoạn 4: HTTPS 443)* | `0.0.0.0/0` |
| `adaptive-learning-app-sg` (ECS) | HTTP 80 · Custom TCP 8080 | `adaptive-learning-scg` |
| `adaptive-learning-db-sg` (RDS) | MySQL/Aurora 3306 | `adaptive-learning-app-sg` |
| `adaptive-learning-redis-sg` (Redis) | Custom TCP 6379 | `adaptive-learning-app-sg` |

**2.2 Outbound**

> SG là **stateful**: đã cho kết nối đi vào thì gói tin trả lời tự động được đi ra, không cần rule outbound.
> Rule outbound chỉ áp dụng cho kết nối do **chính resource đó chủ động mở ra**.

Cách đơn giản (khi học/demo): giữ mặc định **All traffic → 0.0.0.0/0** cho cả 4 SG. Vẫn an toàn, vì inbound đã chặn đúng chỗ và tầng data không có route ra internet.

Cách siết chặt (giống production): xóa rule mặc định, thêm đúng các rule sau:

| SG | Outbound | Destination | Để làm gì |
|---|---|---|---|
| `adaptive-learning-scg` (ALB) | HTTP 80 | `adaptive-learning-app-sg` | Chuyển request tới task frontend |
| | Custom TCP 8080 | `adaptive-learning-app-sg` | Chuyển request + health check tới task backend |
| `adaptive-learning-app-sg` (ECS) | HTTPS 443 | `0.0.0.0/0` | **Bắt buộc**: kéo image ECR, ghi CloudWatch Logs, đọc SSM, gọi S3 (qua NAT / S3 endpoint) |
| | MySQL/Aurora 3306 | `adaptive-learning-db-sg` | Backend gọi RDS |
| | Custom TCP 6379 | `adaptive-learning-redis-sg` | Backend gọi Redis (giai đoạn 3) |
| `adaptive-learning-db-sg` (RDS) | *(không có rule nào)* | — | RDS không chủ động gọi ra đâu |
| `adaptive-learning-redis-sg` (Redis) | *(không có rule nào)* | — | Redis không chủ động gọi ra đâu |

> Thiếu outbound 443 của `adaptive-learning-app-sg` → task báo `CannotPullContainerError` / `ResourceInitializationError`.
> Thiếu outbound 80/8080 của ALB → target group luôn **unhealthy**.

Đi ra internet được hay không còn do **route table** quyết định: tầng app đi qua NAT, tầng data không có đường ra.

> SG là lớp chặn chính: chỉ ALB gọi được ECS, chỉ ECS gọi được RDS/Redis. Không mở 3306/6379 từ `0.0.0.0/0` hay từ CIDR của VPC.

---

## 3. RDS MySQL

1. RDS → Subnet groups → Create `adaptive-db-subnets` · VPC `vpc-adaptive-learning` · chọn **`adaptive-data-2a` + `adaptive-data-2b`**.
2. RDS → Create database `adaptive-db`: MySQL 8.0 · VPC `vpc-adaptive-learning` · subnet group `adaptive-db-subnets` · SG **chỉ** `adaptive-learning-db-sg` · **Public access = No**.

- **Không cần import SQL, không cần máy trung gian**: khi backend khởi động lần đầu, `createDatabaseIfNotExist=true` tạo database `adaptive_learning_db`, rồi **Flyway** chạy `V1__schema.sql` (bảng) và `V2__seed_data.sql` (dữ liệu mẫu).
- Copy **Endpoint** ở tab *Connectivity & security*.

**Lưu mật khẩu DB:** Systems Manager → Parameter Store → Create parameter
- Name `/adaptive-learning/db-password` · Type **SecureString** · Value = mật khẩu master RDS.

---

## 4. Đẩy image lên ECR

```powershell
cd D:\source_code\Adaptive_Learning
.\deploy\push-ecr.ps1
```
Script tự tạo repository `adaptive-backend`, `adaptive-frontend`, build `linux/amd64`, push tag `latest`.
(PowerShell chặn script: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.)

---

## 5. IAM Role

**5.1 `ecsTaskExecutionRole`** — ECS dùng để kéo image, ghi log, đọc mật khẩu
- Chưa có: IAM → Roles → Create role → AWS service → **Elastic Container Service → Elastic Container Service Task** → policy `AmazonECSTaskExecutionRolePolicy` → tên `ecsTaskExecutionRole`.
- Thêm inline policy (JSON): `deploy/iam/execution-role-extra-policy.json`.

**5.2 `adaptive-learning-task-role`** — quyền của **code Java** (gọi S3)
- Create role → **Elastic Container Service Task** → chưa gắn policy (giai đoạn 2 mới thêm) → tên `adaptive-learning-task-role`.

---

## 6. ECS Cluster + Task Definition

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

## 7. Target Group + Application Load Balancer

**7.1 Target group** (Console → mục **EC2** → *Load Balancing* → Target Groups → Create — Target Group và ALB nằm trong menu EC2 của console nhưng **không tạo máy EC2 nào**; *Target type = IP addresses*, VPC `vpc-adaptive-learning`, không register target):

| | `tg-adaptive-frontend` | `tg-adaptive-backend` |
|---|---|---|
| Protocol / Port | HTTP / 80 | HTTP / 8080 |
| Health check path | `/healthz` | `/actuator/health/liveness` |
| Healthy threshold / Interval | 2 / 15s | 2 / 15s |

Sau khi tạo: tab *Attributes* → **Deregistration delay = 30s**.

**7.2 ALB** (Console → mục **EC2** → *Load Balancing* → Load Balancers → Create → Application Load Balancer)
- `alb-adaptive` · Internet-facing · VPC `vpc-adaptive-learning`
- Mappings: **2a → `adaptive-public-2a`**, **2b → `adaptive-public-2b`** (ALB luôn ở tầng public)
- Security group: **chỉ** `adaptive-learning-scg`
- Listener HTTP:80 → default forward **`tg-adaptive-frontend`**
- Sau khi tạo: tab *Attributes* → **Connection idle timeout = 300** giây (upload video lớn tới 500MB).

**7.3 Rule** (Listeners and rules → HTTP:80 → Add rule)
- Condition **Path**: `/api/*`, `/uploads/*`, `/actuator/*` → Forward **`tg-adaptive-backend`** · Priority `10`.

---

## 8. ECS Service (Cluster `adaptive-cluster` → Services → Create)

| Mục | backend | frontend |
|---|---|---|
| Launch type | FARGATE | FARGATE |
| Task definition | `adaptive-backend` | `adaptive-frontend` |
| Desired tasks | **1** (giai đoạn 3 tăng lên 2) | 1 |
| Subnets | **`adaptive-app-2a` + `adaptive-app-2b`** (bỏ chọn subnet public/data) | như backend |
| Security group | **chỉ** `adaptive-learning-app-sg` | như backend |
| Public IP | **OFF** (task ở private, ra ngoài qua NAT) | OFF |
| Load balancer | `alb-adaptive`, listener HTTP:80, TG `tg-adaptive-backend`, container `backend 8080` | TG `tg-adaptive-frontend`, container `frontend 80` |
| Health check grace period | **180** giây (lần đầu Flyway nạp dữ liệu mẫu) | 30 |

---

## 9. Kiểm tra giai đoạn 1

Lấy **DNS name** của ALB rồi mở:
- `http://<DNS>/actuator/health` → `"status":"UP"`, `db: UP` (trang xem tay; ALB thì dùng `/actuator/health/liveness`, chỉ trả `{"status":"UP"}`)
- `http://<DNS>/api/courses` → JSON danh sách khóa học
- `http://<DNS>/` → đăng nhập `admin@example.com` / `admin123`

Log: ECS → service → task → tab **Logs** (lần đầu sẽ thấy Flyway `Migrating schema ... to version "1"`, `"2"`).

---

## 10. Giai đoạn 2 — S3

1. S3 → Create bucket `adaptive-learning-uploads-<số-ngẫu-nhiên>` · region ap-southeast-2 · **giữ Block all public access**.
2. IAM → `adaptive-learning-task-role` → Create inline policy → JSON `deploy/iam/task-role-s3-policy.json` (thay `<BUCKET_NAME>`).
3. Task definition `adaptive-backend` → **Create new revision** → sửa biến:
   `STORAGE_TYPE = s3`, thêm `S3_BUCKET = adaptive-learning-uploads-xxxx`.
4. Service backend → **Update** → revision mới → **Force new deployment**.
5. Thử: admin upload ảnh khóa học / video bài học → file nằm trong bucket (`images/`, `videos/`).

Cách hoạt động: DB vẫn lưu `/uploads/videos/abc.mp4`. Trình duyệt gọi đường dẫn đó → ALB → backend trả **302** sang presigned URL của S3 → video tải thẳng từ S3, backend không phải gửi file nặng.

---

## 11. Giai đoạn 3 — ElastiCache Redis

1. ElastiCache → Subnet groups → Create `adaptive-redis-subnets` (**`adaptive-data-2a` + `adaptive-data-2b`**).
2. Create cache → **Valkey** (tương thích Redis) hoặc Redis OSS → *Design your own cache* → **Cluster cache** (không chọn Serverless)
   → Cluster mode Disabled · `cache.t3.micro` · 0 replica · subnet group trên · SG **chỉ** `adaptive-learning-redis-sg`.
3. Copy **Primary endpoint** (bỏ `:6379`).
4. Task definition backend → new revision: `CACHE_TYPE=redis`, `REDIS_HEALTH=true`, thêm `REDIS_HOST=<endpoint>`, `REDIS_SSL=true` nếu bật *Encryption in transit*.
5. Update service backend → revision mới → Force new deployment → **Desired tasks = 2**.
6. Kiểm tra `/actuator/health` có `redis: UP`. API được cache: `/api/courses`, `/api/courses/trending`, `/api/courses/{id}` (tự xóa cache khi admin thêm/sửa/xóa khóa học).

---

## 12. Giai đoạn 4 — HTTPS (camera cần HTTPS)

Trình duyệt chỉ cho mở webcam trên **HTTPS**.
1. Có tên miền (Route 53 hoặc nhà cung cấp khác).
2. **ACM** (Certificate Manager, region ap-southeast-2) → Request public certificate → xác thực DNS.
3. ALB → Add listener **HTTPS:443** → certificate vừa tạo → default forward `tg-adaptive-frontend` + rule giống mục 7.3.
4. Listener HTTP:80 → đổi default action thành **Redirect to HTTPS 443**.
5. `adaptive-learning-scg` thêm inbound HTTPS 443 từ `0.0.0.0/0`.
6. Trỏ tên miền (CNAME/Alias) về DNS name của ALB.

---

## 13. Cập nhật code

```powershell
.\deploy\push-ecr.ps1
```
→ ECS → service → **Update service → Force new deployment**.
Đổi cấu trúc DB: **không sửa** V1/V2, tạo file mới `V3__mo_ta.sql` trong `db/migration`.

---

## 14. Lỗi thường gặp

| Hiện tượng | Nguyên nhân |
|---|---|
| `CannotPullContainerError` / timeout tới ECR | Service chọn nhầm subnet data; app-sg thiếu outbound 443; `rt-adaptive-app` thiếu route `0.0.0.0/0 → NAT`; NAT đặt nhầm ở subnet private; execution role thiếu policy |
| `ResourceInitializationError ... ssm` | Execution role thiếu inline policy, sai tên parameter, hoặc task không ra được internet (xem dòng trên) |
| ECS chạy nhưng không có log | Task không ra được CloudWatch Logs: kiểm tra NAT/route như trên |
| Target unhealthy, web 502/503 | `adaptive-learning-app-sg` chưa mở 80/8080 từ SG ALB; đã siết outbound mà ALB thiếu 80/8080 → app-sg; backend chưa khởi động xong (tăng grace period) |
| `Communications link failure` | `adaptive-learning-db-sg` chưa cho 3306 từ app-sg, hoặc sai endpoint |
| `Access denied for user 'admin'` | Sai mật khẩu trong SSM |
| `/actuator/health` DOWN vì redis | Chưa có Redis mà `REDIS_HEALTH=true`, hoặc sai `REDIS_SSL`. ALB vẫn healthy (dùng liveness) và API vẫn chạy, chỉ không có cache; log backend có dòng `Cache GET loi` |
| Upload lỗi 502 "Could not upload file to S3" | Sai `S3_BUCKET` hoặc task role chưa có policy S3 |
| Upload video lớn bị ngắt | Chưa tăng ALB idle timeout lên 300s |
| `/api/...` trả về trang HTML | Thiếu rule path trên listener ALB |
| Camera báo cần HTTPS | Làm giai đoạn 4 |

---

## 15. Dọn dẹp — tránh mất tiền

- Nghỉ ngắn: mỗi ECS service → **Desired tasks = 0**; RDS → **Stop temporarily** (tự bật lại sau 7 ngày).
- ElastiCache không có Stop → xóa cluster. ALB tính tiền theo giờ kể cả không có traffic.
- **NAT Gateway** tốn khoảng 0.059 USD/giờ (~43 USD/tháng) cộng phí dữ liệu, cũng không có Stop → nghỉ dài thì **xóa NAT** rồi **Release Elastic IP**. Khi dùng lại: tạo NAT mới, sửa route `0.0.0.0/0` trong `rt-adaptive-app` trỏ sang NAT mới.
- Xóa hẳn: ECS services → cluster → ALB → target groups → ElastiCache → RDS → S3 (xóa object trước) → ECR → CloudWatch log groups → NAT Gateway → Release Elastic IP → VPC (xóa VPC sẽ xóa luôn subnet, route table, IGW, endpoint).
- Đặt **AWS Budgets** cảnh báo 5–10 USD/tháng.
