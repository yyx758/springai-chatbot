# Kafka Outbox 一致性修正收口记录

日期：2026-06-18

## 收口结论

本轮在原 Kafka/outbox 修正基础上补了两个工程质量点：

- 删除旧的 ChatEventProducer，避免保留“先发 Kafka，失败后再写 outbox”的旧语义入口。
- ChatRecordPersistenceService 在序列化 ChatEvent 失败时不再写入 payload_json = "{}"，而是抛出 IllegalStateException，交由事务回滚，避免 chat_record 已写入但 outbox 是无效事件。

## 当前主链路

~~~text
AI 输出完成
-> ChatRecordPersistenceService 同事务写 chat_record + chat_event_outbox
-> 事务成功后 append Redis
-> outbox dispatcher 后台发送 Kafka
-> Kafka Consumer 验证 chat_record 已存在并 ACK
~~~

## 验证命令

~~~bash
mvn -q -pl chatbot-service "-Dtest=ChatRecordPersistenceServiceTest,KafkaReliabilityTest" test
mvn -q -pl chatbot-service -DskipTests package
~~~

## 备注

本次提交只应包含 Kafka/outbox 上下文一致性相关文件，不应混入 RAG、前端模板、求职材料或其他未提交变更。
