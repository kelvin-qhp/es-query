# es-query-service

基于 **官方 `co.elastic.clients:elasticsearch-java` 9.x** 客户端的查询 & 聚合 Service 封装。
纯 Java 17+，不强制 Spring（附带一个 `@Configuration` 装配示例）。

## 能力总览

| 分类 | 能力 |
|---|---|
| 查询 | term/terms/match/match_phrase/match_bool_prefix/prefix/wildcard/regexp/range(数值/日期/keyword)/exists/ids/nested/原生 JSON 逃生舱 |
| 打分 | function_score：gauss/exp/linear 衰减(数值/日期/geo)、field_value_factor、script_score、random_score、纯权重；支持 filter 条件、score_mode/boost_mode/max_boost/min_score |
| 组合 | must / filter / mustNot / should(+minimumShouldMatch)，bool 自由组合 |
| 分页 | from+size、search_after 深分页、searchAll 全量流式遍历 |
| 折叠 | collapse 同值折叠 + inner_hits 取回被折叠文档（自定义名称/条数/字段） |
| 其他 | 高亮、_source 字段裁剪、track_total_hits、timeout、routing、preference |
| 聚合 | terms/long-terms/double-terms、avg/sum/min/max/value_count/cardinality/stats/extended_stats/percentiles、histogram/date_histogram(calendar+fixed)、range/date_range、filter、nested、top_hits、composite 游标遍历 |
| 认证 | Basic / API Key，https + 自签证书开关 |
| 兜底 | `Condition.raw(json)`、`Agg.raw(json)`、`Agg.customize(builder)`、`service.raw(SearchRequest)` |

## 快速开始

```java
// 1. 构建客户端（也可用 ElasticsearchConfig 交给 Spring 装配）
ElasticsearchClient client = EsClientFactory.create(
        new EsProperties().node("localhost", 9200).basicAuth("elastic", "pwd"));
SearchService search = new SearchServiceImpl(client);

// 2. 条件查询 + 分页 + 高亮
SearchRequestDto dto = new SearchRequestDto("products")
        .keyword("手机", "title^3", "summary")          // multi_match
        .filter(Condition.term("status", 1),
                Condition.range("price", 0, 5000))      // 数值区间
        .mustNot(Condition.term("deleted", true))
        .should(1, Condition.match("brand", "huawei"),
                   Condition.match("brand", "xiaomi"))  // 至少命中 1 个
        .sortDesc("create_time")
        .highlight("title")
        .page(1, 20);

SearchResultDto<Product> result = search.searchPage(dto, Product.class);
result.documents.forEach(p -> ...);
result.hits.get(0).highlightText("title");

// 3. 聚合
AggResult agg = search.aggregate(new SearchRequestDto("orders"), List.of(
        Agg.terms("by_brand", "brand").size(10).orderCountDesc()
                .sub(Agg.avg("avg_price", "price"), Agg.topHits("samples", 3)),
        Agg.dateHistogram("trend", "create_time", CalendarInterval.Day, "yyyy-MM-dd")
                .sub(Agg.cardinality("uv", "user_id")),
        Agg.stats("price_stats", "price")));

for (AggResult.Bucket b : agg.buckets("by_brand")) {
    System.out.println(b.key + " " + b.docCount + " " + b.subAggs.get("avg_price"));
}
double avg = agg.avg("avg_price_in_bucket");  // 顶层指标直接读
Map<String, Long> dist = agg.termsMap("by_brand");

// 4. search_after 深分页
SearchRequestDto after = new SearchRequestDto("products").size(100).sortAsc("price");
SearchResultDto<Product> p1 = search.searchAfter(after, Product.class);
after.searchAfter = p1.searchAfterCursor;                 // 游标续传
SearchResultDto<Product> p2 = search.searchAfter(after, Product.class);

// 5. 全量遍历（每批 1000 条，内存安全）
long total = search.searchAll(new SearchRequestDto("products"), 1000,
        Product.class, batch -> batch.forEach(this::handle));

// 6. composite 聚合游标遍历（大基数分组）
search.compositeEach(new SearchRequestDto("orders"),
        Agg.composite("by_user",
                Agg.sources("user", Agg.termsSource("user_id")), null)
                .sub(Agg.sum("amount", "amount")),
        bucket -> handle(bucket.compositeKey, bucket.subAggs));

// 7. 查询 + 聚合一次拿
Map.Entry<SearchResultDto<Product>, AggResult> both =
        search.searchWithAggs(dto, List.of(Agg.terms("by_brand", "brand")), Product.class);
```

## 日期区间查询

9.x 将 range query 拆分为 number/date/term 三种类型化查询，封装已自动路由：

```java
Condition.range("price", 10, 100)                              // number 区间
Condition.dateRange("create_time", "2026-01-01", true,
        "2026-02-01", false)                                   // date 区间（支持 now-1d 表达式）
Condition.range("version", "1.2.0", true, "2.0.0", false)      // keyword term 区间
```

## function_score 自定义打分

非空时最终 query 自动包一层 `function_score`（对 `rawQuery` 同样生效）：

```java
SearchRequestDto dto = new SearchRequestDto("products")
        .keyword("手机", "title^3")
        .functionScore(FunctionScore.of(
                        // 时间高斯衰减：30 天半衰，仅 status=1 生效，乘 2 倍权重
                        ScoreFunction.dateGauss("create_time", "now", "30d", 0.5)
                                .weight(2.0).when(Condition.term("status", 1)),
                        // 数值衰减 + offset
                        ScoreFunction.gauss("price", 100, 50, 0.5).offset(10),
                        // field_value_factor：log1p(popularity*1.2)，缺失按 1.0
                        ScoreFunction.fieldValue("popularity", 1.2, 1.0)
                                .modifier(FieldValueFactorModifier.Log1p),
                        // 脚本打分（_score 即基础得分）
                        ScoreFunction.script("params.f * _score", Map.of("f", 3)),
                        // 随机打分（同文档同分，需给定种子字段）
                        ScoreFunction.random("_seq_no"))
                        .scoreMode("sum")       // 多函数组合：multiply/sum/avg/first/max/min
                        .boostMode("multiply")  // 与 query 得分结合：multiply/replace/sum/avg/max/min
                        .maxBoost(12.0)
                        .minScore(1.0));
```

- 纯权重：`FunctionScore.weight(5.0, Condition.term("vip", true))` 对命中文档整体乘 5。
- geo 衰减：`ScoreFunction.geoGauss("location", "40.0,-105.0", "50km", 0.5)`。
- 衰减 origin/scale/offset 统一按 JSON 原样序列化（数值传 Number，日期/距离传 "30d"、"50km" 表达式）。

## collapse 折叠

同一字段值只保留排名最高的一条，`inner_hits` 可把被折叠掉的文档一并带回：

```java
SearchRequestDto dto = new SearchRequestDto("orders")
        .filter(Condition.term("status", 1))
        .collapse(Collapse.on("user_id")      // keyword/数值字段；text 用 xxx.keyword
                .innerHits("others", 3)       // 名称 + 条数，默认名 collapse_inner
                .innerHitsFields("order_no", "amount"))
        .sortDesc("amount");
```

- 折叠只影响 hits，不影响聚合与 `total`（要统计去重数请用 `cardinality` 聚合）。
- `collapse` + `search_after` 时排序必须包含折叠字段（或 `_shard_doc`）。
- 被折叠文档从 `HitWrapper.innerHits()` 取：`Map<String, InnerHitsResult>`，按 inner_hits 名称拿到 `hits().hits()`（source 为 `JsonData`）。

## Spring Boot 集成

`com.example.es.config.ElasticsearchConfig` 提供 `EsProperties / ElasticsearchClient / SearchService` 三个 Bean，
配置项：

```yaml
es:
  nodes: 10.0.0.1:9200,10.0.0.2:9200
  scheme: https
  username: elastic
  password: "***"
  # 或 apiKey: "xxx"
  ssl-verify: false      # 自签证书时
```

## 结构

两个 Maven 模块（父工程 `es-query`）：

```
es-query/
├── es-query-service-api          纯 API（仅依赖 elasticsearch-java，调用方可单独引用）
│   └── com.example.es
│       ├── service    SearchService 接口
│       ├── dto        Condition / SearchRequestDto / FunctionScore / ScoreFunction / Collapse /
│       │              SearchResultDto / HitWrapper / AggResult / Paginate
│       ├── core       Agg(聚合 DSL)
│       └── exception  EsException
└── es-query-service              实现（依赖 api 模块）
    └── com.example.es
        ├── config     EsProperties / EsClientFactory / ElasticsearchConfig(Spring 示例)
        ├── util       QueryBuilderUtil(条件->Query) / JsonUtils
        └── impl       SearchServiceImpl
```

构建：在 `es-query` 根目录执行 `mvn clean package`（reactor 先 api 后 service；离线环境 `install` 插件不可用，请在根目录跑 reactor，单独构建 service 会解析不到 api 快照）。

## 验证

- `mvn test`：23 个单测全部通过（序列化为标准 ES JSON），含 function_score 打分、collapse 折叠与 nested/filter/dateHistogram/range/composite 聚合用例。测试类按被测类命名并与其同包：`QueryBuilderUtilTest`、`SearchServiceImplTest`（impl 模块）、`HitWrapperTest`（api 模块）。
- `SearchServiceIntegrationTest` 为端到端示例，本地起 ES 9 后去掉 `@Disabled` 运行。

## 注意

- 封装针对 `elasticsearch-java` **9.0.3**（9.x API：类型化 RangeQuery、内建 Rest5ClientTransport、`sterms()/lterms()/dterms()` 聚合结果等），8.x 客户端不适用。
- 客户端版本需与 ES 服务端大版本一致（9.x 客户端可连 8.18+ 服务端）。
- `searchAll`/`searchAfter` 依赖确定性排序；不指定时会补 `_shard_doc` 排序。
