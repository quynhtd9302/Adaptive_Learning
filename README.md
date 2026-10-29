# Hệ thống học tập thích ứng

Đây là đồ án xây dựng nền tảng học tập trực tuyến có quiz thích ứng, theo dõi tiến trình học, dashboard cho học viên/quản trị viên và nền tảng backend cho việc tích hợp Webcam Focus/Face-A ở giai đoạn sau.

## Công nghệ sử dụng

### Backend

- Java 17
- Spring Boot 3.3.6
- Spring Web
- Spring Data JPA
- Jakarta Validation
- MySQL
- Maven

### Frontend

- React 19
- Vite
- React Router
- Axios
- CSS thuần

### Database

- MySQL 8 (local) / Amazon RDS (AWS)
- Schema và dữ liệu mẫu do **Flyway** tự chạy khi backend khởi động:
  - `backend/src/main/resources/db/migration/V1__schema.sql` — toàn bộ bảng
  - `backend/src/main/resources/db/migration/V2__seed_data.sql` — dữ liệu mẫu (users, 8 khóa học, bài học, câu hỏi quiz)

### Hạ tầng AWS

VPC (Public / Private / Data subnet) → NAT Gateway → Security Group → Application Load Balancer → ECS Fargate → RDS MySQL → S3 → ElastiCache Redis.
Hướng dẫn từng bước: **[docs/DEPLOY_AWS.md](docs/DEPLOY_AWS.md)**.

## Cấu trúc chính

```text
Adaptive_Learning
├── backend                     Spring Boot API (Dockerfile)
│   └── src/main/resources
│       ├── application.yml           cấu hình chung
│       ├── application-dev.yml       máy local (mặc định)
│       ├── application-prod.yml      AWS ECS (SPRING_PROFILES_ACTIVE=prod)
│       └── db/migration              V1__schema.sql, V2__seed_data.sql
├── frontend                    React + Vite, chạy bằng nginx (Dockerfile, nginx.conf)
├── deploy                      task definition ECS, IAM policy, script đẩy image lên ECR
├── docs/DEPLOY_AWS.md          hướng dẫn deploy AWS
├── docker-compose.yml          CHỈ để chạy thử ở máy local (AWS dùng ECS Task Definition)
└── PROJECT_REQUIREMENTS.md
```

## Cách 1 — Chạy thử toàn bộ bằng Docker ở máy local

> Chỉ dùng trên máy local. Trên AWS không dùng docker-compose và EC2 — xem [docs/DEPLOY_AWS.md](docs/DEPLOY_AWS.md).

```bash
docker compose up -d --build
```

- Web: `http://localhost:3000`
- API: `http://localhost:8080/api/courses`
- Health: `http://localhost:8080/actuator/health`
- MinIO (giả lập S3): `http://localhost:9001` (minioadmin / minioadmin)

Xóa sạch: `docker compose down -v`.

## Cách 2 — Chạy từng phần khi code

Chỉ cần MySQL đang chạy, **không cần import SQL**: backend tự tạo database `adaptive_learning_db`, bảng và dữ liệu mẫu.

Mặc định backend dùng `root` / mật khẩu trống. Nếu máy dùng mật khẩu khác, đặt biến môi trường trước khi chạy (không cần sửa file):

```bash
# PowerShell
$env:DB_PASSWORD="123456"
```

> Database `adaptive_learning_db` đã có sẵn bảng từ trước: Flyway coi như đã chạy V1, V2 và không đụng vào dữ liệu cũ.
> Muốn dữ liệu sạch theo seed mới thì `DROP DATABASE adaptive_learning_db;` rồi chạy lại backend.

## Chạy backend

```bash
cd backend
mvn spring-boot:run
```

Backend chạy tại:

```text
http://localhost:8080
```

API base URL:

```text
http://localhost:8080/api
```

File upload local được phục vụ qua:

```text
http://localhost:8080/uploads/...
```

## Chạy frontend

Cài dependency:

```bash
cd frontend
npm install
```

Chạy dev server:

```bash
npm run dev
```

Frontend chạy tại:

```text
http://localhost:5173
```

## Tài khoản demo

Dữ liệu mẫu (`V2__seed_data.sql`) có các tài khoản (mật khẩu đã mã hóa BCrypt):

| Vai trò | Email | Password |
|---|---|---|
| Học viên | `an.student@example.com` | `123456` |
| Học viên | `binh.student@example.com` | `123456` |
| Admin | `admin@example.com` | `admin123` |

## Lệnh kiểm tra

Frontend lint:

```bash
cd frontend
npm run lint
```

Frontend build:

```bash
cd frontend
npm run build
```

Backend test:

```bash
cd backend
mvn test
```

## Flow demo chính

### Học viên

1. Đăng nhập.
2. Xem danh sách khóa học.
3. Mở chi tiết khóa học.
4. Xem bài học/video.
5. Bật/tắt webcam focus UI.
6. Đánh dấu hoàn thành bài học.
7. Làm quiz.
8. Xem feedback.
9. Xem dashboard học tập.

### Admin

1. Đăng nhập bằng tài khoản admin.
2. Mở dashboard admin.
3. Tạo/sửa/xóa khóa học.
4. Upload ảnh đại diện khóa học.
5. Tạo/sửa/xóa bài học.
6. Upload video bài học.
7. Tạo/sửa/xóa câu hỏi quiz.
8. Quản lý file upload (local hoặc S3).

## Ghi chú hiện tại

- Hệ thống chưa dùng Spring Security/JWT.
- API backend chưa có xác thực (mọi người biết địa chỉ đều gọi được) — cần thêm Spring Security/JWT trước khi dùng thật.
- Route guard frontend dựa trên `localStorage.user`.
- Webcam hiện mới là UI bật/tắt camera, chưa tích hợp `face-api.js`.
- Focus logs backend đã có API, nhưng frontend chưa tự gửi log từ AI.
