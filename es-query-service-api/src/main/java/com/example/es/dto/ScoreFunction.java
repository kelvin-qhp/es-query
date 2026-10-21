package com.example.es.dto;

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;

/**
 * function_score 中的单个打分函数：高斯/指数/线性衰减、field_value_factor、script_score、random_score、纯权重。
 * 通过静态工厂创建，挂到 {@link FunctionScore} 上。
 */
public class ScoreFunction {

    public enum Type {
        GAUSS, EXP, LINEAR, FIELD_VALUE, SCRIPT, RANDOM, WEIGHT
    }

    public Type type;

    /** GAUSS/EXP/LINEAR：衰减字段（数值/日期/geo_point） */
    public String field;
    /** GAUSS/EXP/LINEAR：原点。数值或日期表达式（"now"）；geo 为 "lat,lon" 字符串 */
    public Object origin;
    /** GAUSS/EXP/LINEAR：衰减尺度。数值、日期表达式（"10d"）或距离（"50km"） */
    public Object scale;
    /** GAUSS/EXP/LINEAR：偏移，落在此范围内的不衰减 */
    public Object offset;
    /** GAUSS/EXP/LINEAR：到达 scale 时的衰减系数 (0,1)，默认 0.5 */
    public Double decay;
    /** GAUSS/EXP/LINEAR：true 走 DateDecayFunction（origin/scale 为 String），false 走 NumericDecayFunction */
    public boolean date;
    /** GAUSS/EXP/LINEAR：true 走 GeoDecayFunction（origin 为 "lat,lon"，scale 为 "50km"） */
    public boolean geo;

    /** FIELD_VALUE：目标数值字段 */
    public String valueField;
    /** FIELD_VALUE：乘数因子，默认 1 */
    public Double factor;
    /** FIELD_VALUE：字段缺失时的替代值 */
    public Double missing;
    /** FIELD_VALUE：log/sqrt/reciprocal 等变换 */
    public FieldValueFactorModifier modifier;

    /** SCRIPT：脚本源码，用 params._score 取基础得分 */
    public String scriptSource;
    /** SCRIPT：脚本参数（值须可被 Jackson 序列化） */
    public java.util.Map<String, Object> scriptParams;

    /** RANDOM：随机种子字段（如 _seq_no），保证同文档同分 */
    public String seedField;

    /** WEIGHT：权重（所有类型都可设，作用于该函数得分） */
    public Double weight;

    /** 命中该 filter 的文档才应用此函数 */
    public Condition filter;
    /** 多个 filter 条件时与 filter 一起 AND 组合 */
    public final java.util.List<Condition> filters = new java.util.ArrayList<>();

    public static ScoreFunction gauss(String field, Object origin, Object scale, Double decay) {
        return decayOf(Type.GAUSS, field, origin, scale, decay);
    }

    public static ScoreFunction exp(String field, Object origin, Object scale, Double decay) {
        return decayOf(Type.EXP, field, origin, scale, decay);
    }

    public static ScoreFunction linear(String field, Object origin, Object scale, Double decay) {
        return decayOf(Type.LINEAR, field, origin, scale, decay);
    }

    /** 日期衰减（date 字段，origin 如 "now"，scale/offset 如 "10d"） */
    public static ScoreFunction dateGauss(String field, String origin, String scale, Double decay) {
        ScoreFunction sf = decayOf(Type.GAUSS, field, origin, scale, decay);
        sf.date = true;
        return sf;
    }

    public static ScoreFunction dateExp(String field, String origin, String scale, Double decay) {
        ScoreFunction sf = decayOf(Type.EXP, field, origin, scale, decay);
        sf.date = true;
        return sf;
    }

    public static ScoreFunction dateLinear(String field, String origin, String scale, Double decay) {
        ScoreFunction sf = decayOf(Type.LINEAR, field, origin, scale, decay);
        sf.date = true;
        return sf;
    }

    /** geo_point 距离衰减（origin 为 "lat,lon"，scale 为 "50km"） */
    public static ScoreFunction geoGauss(String field, String origin, String scale, Double decay) {
        ScoreFunction sf = decayOf(Type.GAUSS, field, origin, scale, decay);
        sf.geo = true;
        return sf;
    }

    private static ScoreFunction decayOf(Type type, String field, Object origin, Object scale, Double decay) {
        ScoreFunction sf = new ScoreFunction();
        sf.type = type;
        sf.field = field;
        sf.origin = origin;
        sf.scale = scale;
        sf.decay = decay;
        return sf;
    }

    public static ScoreFunction fieldValue(String field, double factor, Double missing) {
        ScoreFunction sf = new ScoreFunction();
        sf.type = Type.FIELD_VALUE;
        sf.valueField = field;
        sf.factor = factor;
        sf.missing = missing;
        return sf;
    }

    public static ScoreFunction script(String source, java.util.Map<String, Object> params) {
        ScoreFunction sf = new ScoreFunction();
        sf.type = Type.SCRIPT;
        sf.scriptSource = source;
        sf.scriptParams = params;
        return sf;
    }

    public static ScoreFunction random(String seedField) {
        ScoreFunction sf = new ScoreFunction();
        sf.type = Type.RANDOM;
        sf.seedField = seedField;
        return sf;
    }

    /** 纯权重函数：整体乘权重，不需要具体打分函数 */
    public static ScoreFunction pureWeight(double weight) {
        ScoreFunction sf = new ScoreFunction();
        sf.type = Type.WEIGHT;
        sf.weight = weight;
        return sf;
    }

    /** 仅对命中 filter 的文档应用该函数，返回自身便于链式调用 */
    public ScoreFunction when(Condition filter) {
        this.filter = filter;
        return this;
    }

    /** 多条件 filter（AND 组合） */
    public ScoreFunction when(Condition first, Condition... more) {
        this.filter = first;
        this.filters.addAll(java.util.Arrays.asList(more));
        return this;
    }

    public ScoreFunction weight(Double weight) {
        this.weight = weight;
        return this;
    }

    /** 衰减 offset（需按字段类型传 Number / 日期表达式 / 距离字符串） */
    public ScoreFunction offset(Object offset) {
        this.offset = offset;
        return this;
    }

    public ScoreFunction decay(Double decay) {
        this.decay = decay;
        return this;
    }

    public ScoreFunction modifier(FieldValueFactorModifier modifier) {
        this.modifier = modifier;
        return this;
    }
}
