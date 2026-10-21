package com.example.es.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * function_score 包装配置：函数列表 + 多函数组合方式 + 基础得分结合方式。
 * 挂到 SearchRequestDto.functionScore() 后，最终 query 会被包一层 function_score。
 *
 * <pre>
 * new SearchRequestDto("products")
 *         .keyword("手机")
 *         .functionScore(FunctionScore.of(
 *                 ScoreFunction.dateGauss("create_time", "now", "30d", 0.5).weight(2.0),
 *                 ScoreFunction.fieldValue("popularity", 1.2, 1.0).modifier(FieldValueFactorModifier.Log1p))
 *                 .scoreMode("sum").boostMode("multiply"));
 * </pre>
 */
public class FunctionScore {

    public final List<ScoreFunction> functions = new ArrayList<>();

    /** 多个函数命中同一文档时的组合方式：multiply/sum/avg/first/max/min（默认 multiply） */
    public String scoreMode;
    /** 函数结果与基础 query 得分的结合方式：multiply/replace/sum/avg/max/min（默认 multiply） */
    public String boostMode;
    public Double maxBoost;
    /** 低于该得分的文档被过滤掉 */
    public Double minScore;

    public static FunctionScore of(ScoreFunction... functions) {
        FunctionScore fs = new FunctionScore();
        fs.functions.addAll(Arrays.asList(functions));
        return fs;
    }

    public FunctionScore add(ScoreFunction... functions) {
        this.functions.addAll(Arrays.asList(functions));
        return this;
    }

    /** 带条件的纯权重函数：命中所有 cond 的文档整体乘 weight */
    public FunctionScore weight(double weight, Condition... cond) {
        ScoreFunction sf = ScoreFunction.pureWeight(weight);
        if (cond.length > 0) {
            sf.filter = cond[0];
            sf.filters.addAll(Arrays.asList(cond).subList(1, cond.length));
        }
        functions.add(sf);
        return this;
    }

    public FunctionScore scoreMode(String scoreMode) {
        this.scoreMode = scoreMode;
        return this;
    }

    public FunctionScore boostMode(String boostMode) {
        this.boostMode = boostMode;
        return this;
    }

    public FunctionScore maxBoost(double maxBoost) {
        this.maxBoost = maxBoost;
        return this;
    }

    public FunctionScore minScore(double minScore) {
        this.minScore = minScore;
        return this;
    }
}
