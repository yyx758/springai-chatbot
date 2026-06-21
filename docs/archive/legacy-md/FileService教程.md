# 文件服务（file-service）搭建教程

## 一、概述

文件服务是独立的微服务，负责统一管理项目中的文件上传、下载、存储和图片处理。

### 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        Gateway (:9000)                       │
│                   统一路由 + JWT 鉴权                         │
└───────┬──────────────┬──────────────┬───────────────────────┘
        │              │              │
        ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│chatbot-service│ │ file-service │ │  未来服务...  │
│    (:8080)   │ │   (:8081)    │ │              │
└──────┬───────┘ └──────┬───────┘ └──────────────┘
       │                │
       │   Kafka 事件    │
       ├────────────────┤
       ▼                ▼
┌──────────┐    ┌──────────────┐
│  MySQL   │    │  本地磁盘/    │
│  Redis   │    │  MinIO 对象  │
│  Kafka   │    │  存储        │
└──────────┘    └──────────────┘
```

### 解决的问题

| 问题 | 改造前 | 改造后 |
|------|--------|--------|
| 图片存储 | Base64 存 MySQL（体积+33%） | 二进制文件存磁盘/MinIO |
| 文件管理 | 无 | 统一上传/下载/删除 API |
| 图片处理 | 无 | 自动压缩 + 缩略图 |
| 知识库文档 | 仅纯文本 | 支持 PDF/TXT/DOCX 上传 |

---

## 二、项目结构

```
file-service/
├── pom.xml                          # Maven 配置
├── Dockerfile                       # Docker 镜像构建
├── src/main/java/com/example/file/
│   ├── FileServiceApplication.java  # 启动类
│   ├── controller/
│   │   └── FileController.java      # REST API 接口
│   ├── service/
│   │   ├── FileService.java         # 文件业务逻辑
│   │   └── ImageProcessor.java      # 图片压缩 + 缩略图
│   ├── storage/
│   │   ├── FileStorage.java         # 存储接口（抽象）
│   │   └── LocalStorage.java        # 本地磁盘存储实现
│   ├── entity/
│   │   └── FileRecord.java          # 文件元数据实体
│   ├── mapper/
│   │   └── FileRecordMapper.java    # MyBatis-Plus Mapper
│   └── config/
│       └── StorageConfig.java       # 存储配置
└── src/main/resources/
    ├── application.yml              # 应用配置
    └── db/migration/
        └── V1__create_file_record.sql  # 建表脚本
```

---

## 三、核心组件详解

### 3.1 存储层抽象（FileStorage 接口）

```java
public interface FileStorage {
    String store(InputStream data, String fileKey, String contentType);  // 存储
    InputStream load(String fileKey);                                     // 读取
    boolean delete(String fileKey);                                       // 删除
    String getUrl(String fileKey);                                        // 获取访问URL
    boolean exists(String fileKey);                                       // 检查存在
}
```

**设计模式：策略模式**

- `LocalStorage` — 本地磁盘存储（开发环境）
- `MinioStorage` — MinIO 对象存储（生产环境，可扩展）

通过配置切换：
```yaml
file:
  storage:
    type: LOCAL  # 或 MINIO
```

### 3.2 图片处理（ImageProcessor）

上传图片时自动处理：

1. **格式校验** — 只允许 jpg/png/gif/webp
2. **大小限制** — 最大 10MB
3. **压缩** — JPEG 超过 2MB 自动压缩到 80% 质量
4. **缩略图** — 生成 200x200 缩略图（保持比例）

使用 `Thumbnailator` 库实现：
```java
Thumbnails.of(input)
    .size(200, 200)
    .keepAspectRatio(true)
    .outputFormat("jpg")
    .toOutputStream(output);
```

### 3.3 文件元数据（file_record 表）

```sql
CREATE TABLE file_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_key        VARCHAR(255) NOT NULL UNIQUE,  -- 文件唯一键（如 2026/05/23/abc123.jpg）
    original_name   VARCHAR(500) NOT NULL,          -- 原始文件名
    content_type    VARCHAR(100) NOT NULL,          -- MIME 类型
    file_size       BIGINT NOT NULL,                -- 文件大小（字节）
    storage_type    VARCHAR(20) DEFAULT 'LOCAL',    -- 存储类型
    storage_path    VARCHAR(1000) NOT NULL,         -- 存储路径
    thumbnail_key   VARCHAR(255) NULL,              -- 缩略图文件键
    uploader_id     BIGINT NOT NULL,                -- 上传者用户 ID
    biz_type        VARCHAR(50) NOT NULL,           -- 业务类型
    biz_id          VARCHAR(100) NULL,              -- 关联业务 ID
    download_count  INT DEFAULT 0,                  -- 下载次数
    created_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**biz_type 业务类型说明：**

| 类型 | 说明 |
|------|------|
| `CHAT_IMAGE` | 聊天图片 |
| `KNOWLEDGE_DOC` | 知识库文档 |
| `AVATAR` | 用户头像 |

---

## 四、API 接口文档

### 4.1 文件上传

```
POST /api/files/upload
Content-Type: multipart/form-data
Authorization: Bearer <token>  （通过 Gateway 时需要）

参数：
  file      - 文件（必填）
  bizType   - 业务类型（必填）
  bizId     - 关联业务 ID（可选）

返回：
{
  "success": true,
  "data": {
    "fileKey": "2026/05/23/abc123def456.jpg",
    "url": "/api/files/2026/05/23/abc123def456.jpg",
    "thumbnailUrl": "/api/files/2026/05/23/abc123def456_thumb.jpg",
    "originalName": "photo.jpg",
    "fileSize": 102400,
    "contentType": "image/jpeg"
  }
}
```

### 4.2 文件下载

```
GET /api/files/{fileKey}

返回：文件二进制流 + Content-Type Header
```

### 4.3 获取文件信息

```
GET /api/files/{fileKey}/info

返回：
{
  "success": true,
  "data": {
    "id": 1,
    "fileKey": "2026/05/23/abc123def456.jpg",
    "originalName": "photo.jpg",
    "contentType": "image/jpeg",
    "fileSize": 102400,
    "bizType": "CHAT_IMAGE",
    "downloadCount": 5,
    ...
  }
}
```

### 4.4 删除文件

```
DELETE /api/files/{fileKey}

返回：{"success": true, "message": "文件删除成功"}
```

### 4.5 批量查询

```
POST /api/files/batch
Content-Type: application/json

{
  "fileKeys": ["2026/05/23/abc.jpg", "2026/05/23/def.jpg"]
}

返回：
{
  "success": true,
  "data": [...]
}
```

---

## 五、启动方式

### 5.1 本地开发启动

```powershell
# 1. 确保 MySQL、Redis、Nacos 已启动
docker-compose up -d mysql redis nacos

# 2. 启动 file-service
cd d:\develop\workspace\idea\ideaprojects\springaI-chatbot\file-service
mvn spring-boot:run
```

启动成功日志：
```
Tomcat started on port(s): 8081
FileServiceApplication started in 3.2 seconds
```

### 5.2 Docker 启动

```powershell
# 构建并启动所有服务
docker-compose up -d file-service

# 查看日志
docker-compose logs -f file-service
```

---

## 六、测试验证

### 6.1 上传图片测试

```powershell
# 准备一张测试图片 test.jpg

# 直接访问 file-service
curl -F "file=@test.jpg" -F "bizType=CHAT_IMAGE" http://localhost:8081/api/files/upload

# 通过 Gateway 访问（需要先登录获取 token）
curl -F "file=@test.jpg" -F "bizType=CHAT_IMAGE" -H "Authorization: Bearer <token>" http://localhost:9000/api/files/upload
```

### 6.2 下载文件测试

```powershell
# 使用上传返回的 fileKey
curl http://localhost:8081/api/files/2026/05/23/abc123.jpg -o downloaded.jpg
```

### 6.3 查看缩略图

```powershell
# 缩略图文件键在上传返回的 thumbnailUrl 中
curl http://localhost:8081/api/files/2026/05/23/abc123_thumb.jpg -o thumb.jpg
```

### 6.4 查看上传目录

```powershell
# 本地存储的文件在 ./uploads/ 目录下
ls ./uploads/2026/05/23/
```

---

## 七、与 chatbot-service 对接

### 7.1 新增接口

chatbot-service 新增了 `/api/chat/stream/filekey` 接口，支持通过 fileKey 传递图片：

```json
POST /api/chat/stream/filekey
Content-Type: application/json

{
  "message": "描述一下这张图片",
  "imageFileKey": "2026/05/23/abc123.jpg",
  "sessionId": "user1_xxx"
}
```

### 7.2 前端调用流程

```javascript
// 第一步：上传图片到 file-service
const formData = new FormData();
formData.append('file', imageFile);
formData.append('bizType', 'CHAT_IMAGE');

const uploadRes = await fetch('/api/files/upload', {
    method: 'POST',
    body: formData
});
const { data: { fileKey } } = await uploadRes.json();

// 第二步：发送聊天请求（带 fileKey）
const chatRes = await fetch('/api/chat/stream/filekey', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        message: '描述一下这张图片',
        imageFileKey: fileKey,
        sessionId: sessionId
    })
});
```

### 7.3 数据存储变化

| 字段 | 改造前 | 改造后 |
|------|--------|--------|
| chat_record.image_data | `data:image/jpeg;base64,/9j/4AAQ...` | `filekey:2026/05/23/abc123.jpg` |

- 改造前：存储完整的 Base64 字符串（体积 +33%）
- 改造后：只存储文件键引用（约 40 字节）

---

## 八、配置说明

### application.yml 完整配置

```yaml
server:
  port: 8081

spring:
  application:
    name: file-service

  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:localhost:8848}

  datasource:
    url: jdbc:mysql://localhost:3306/chatbot?...
    username: root
    password: yyx2005yyx

  flyway:
    enabled: true

  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

# 文件存储配置
file:
  storage:
    type: LOCAL           # LOCAL 或 MINIO
    local:
      base-path: ./uploads   # 本地存储路径
      url-prefix: /api/files # URL 前缀
```

---

## 九、扩展：接入 MinIO 对象存储

生产环境建议使用 MinIO 替代本地磁盘存储。

### 9.1 启动 MinIO

```yaml
# 添加到 docker-compose.yml
minio:
  image: minio/minio:latest
  container_name: minio
  ports:
    - "9000:9000"
    - "9001:9001"
  environment:
    MINIO_ROOT_USER: minioadmin
    MINIO_ROOT_PASSWORD: minioadmin
  volumes:
    - minio-data:/data
  command: server /data --console-address ":9001"
```

### 9.2 添加 MinioStorage 实现

```java
@Slf4j
@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "MINIO")
public class MinioStorage implements FileStorage {

    private MinioClient minioClient;

    @Value("${file.storage.minio.endpoint}")
    private String endpoint;

    @Value("${file.storage.minio.access-key}")
    private String accessKey;

    @Value("${file.storage.minio.secret-key}")
    private String secretKey;

    @Value("${file.storage.minio.bucket}")
    private String bucket;

    @PostConstruct
    public void init() {
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        // 确保 bucket 存在
        createBucketIfNotExists();
    }

    @Override
    public String store(InputStream data, String fileKey, String contentType) {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(fileKey)
                .stream(data, -1, 10485760)
                .contentType(contentType)
                .build());
        return fileKey;
    }

    // ... 其他方法
}
```

### 9.3 修改配置

```yaml
file:
  storage:
    type: MINIO
    minio:
      endpoint: http://localhost:9000
      access-key: minioadmin
      secret-key: minioadmin
      bucket: chatbot-files
```

---

## 十、常见问题

### Q1: 上传报 413 Request Entity Too Large

检查 nginx 配置：
```nginx
client_max_body_size 10m;
```

### Q2: 图片上传后无法下载

检查 `./uploads/` 目录权限，确保应用有读写权限。

### Q3: 缩略图没有生成

只有图片文件（jpg/png/gif/webp）会生成缩略图，其他文件类型不会。

### Q4: 如何切换到 MinIO？

1. 启动 MinIO 服务
2. 添加 `MinioStorage.java` 实现类
3. 修改 `application.yml` 中 `file.storage.type` 为 `MINIO`
4. 配置 MinIO 连接信息

---

## 十一、文件清单

| 文件 | 说明 |
|------|------|
| `file-service/pom.xml` | Maven 依赖配置 |
| `file-service/Dockerfile` | Docker 镜像构建文件 |
| `file-service/src/main/java/.../FileServiceApplication.java` | 启动类 |
| `file-service/src/main/java/.../controller/FileController.java` | REST API 控制器 |
| `file-service/src/main/java/.../service/FileService.java` | 文件业务逻辑 |
| `file-service/src/main/java/.../service/ImageProcessor.java` | 图片处理（压缩+缩略图） |
| `file-service/src/main/java/.../storage/FileStorage.java` | 存储接口 |
| `file-service/src/main/java/.../storage/LocalStorage.java` | 本地存储实现 |
| `file-service/src/main/java/.../entity/FileRecord.java` | 文件元数据实体 |
| `file-service/src/main/java/.../mapper/FileRecordMapper.java` | MyBatis-Plus Mapper |
| `file-service/src/main/java/.../config/StorageConfig.java` | 存储配置类 |
| `file-service/src/main/resources/application.yml` | 应用配置 |
| `file-service/src/main/resources/db/migration/V1__create_file_record.sql` | 建表脚本 |
