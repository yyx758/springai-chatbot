# RAG 文档导航

本目录保存 Hybrid RAG、Elasticsearch 关键词召回、索引优化和召回测试相关文档。

## 推荐阅读顺序

| 顺序 | 文档 | 说明 |
| --- | --- | --- |
| 1 | [混合 RAG 全链路说明](混合RAG全链路说明.md) | 当前 RAG 主文档。 |
| 2 | [ES 关键词召回说明](ES关键词召回说明.md) | ES 作为关键词召回层的设计和验证。 |
| 3 | [RAG 索引优化说明](RAG索引优化说明.md) | MySQL FULLTEXT 与 ES 索引优化依据。 |
| 4 | [RAG 召回率优化执行报告](RAG召回率优化执行报告.md) | ES ngram、query rewrite、向量候选兜底等执行结果。 |
| 5 | [RAG 关键词检索重构执行报告](RAG关键词检索重构执行报告.md) | ES BM25、QueryEnhancer、RRF service、debug 接口和 v3 索引改造。 |
| 6 | [RAG 召回实际查询测试报告](RAG召回实际查询测试报告.md) | 修改前后召回对比和实际查询命中。 |
| 7 | [RAG 分词器优化计划](RAG分词器优化计划.md) | 分词器和关键词链路的后续优化计划。 |
| 8 | [Elasticsearch 试运行记录](Elasticsearch试运行记录.md) | ES 启动、生产默认启用、索引重建、验证和回滚命令。 |

## 当前实现边界

- 语义召回：PGVector。
- 关键词召回：Elasticsearch 优先，MySQL FULLTEXT fallback。
- 排序融合：HybridRanker / RRF 相关服务。
- ES 只作为关键词召回层，不承担向量检索。
- 生产环境内存有限，ES 需要保留 fallback 方案。
