/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.metrics;

import com.google.common.collect.Lists;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram.Order;
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentile;
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentiles;
import org.elasticsearch.search.aggregations.metrics.percentiles.PercentilesMethod;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.global;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.percentiles;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 *
 */
public class HDRPercentilesTests extends AbstractNumericTests {

    private static double[] randomPercentiles() {
        final int length = randomIntBetween(1, 20);
        final double[] percentiles = new double[length];
        for (int i = 0; i < percentiles.length; ++i) {
            switch (randomInt(20)) {
            case 0:
                percentiles[i] = 0;
                break;
            case 1:
                percentiles[i] = 100;
                break;
            default:
                percentiles[i] = randomDouble() * 100;
                break;
            }
        }
        Arrays.sort(percentiles);
        Loggers.getLogger(HDRPercentilesTests.class).info("Using percentiles={}", Arrays.toString(percentiles));
        return percentiles;
    }

    private static int randomSignificantDigits() {
        return randomIntBetween(0, 5);
    }

    private void assertConsistent(double[] pcts, Percentiles percentiles, long minValue, long maxValue, int numberSigDigits) {
        final List<Percentile> percentileList = Lists.newArrayList(percentiles);
        assertEquals(pcts.length, percentileList.size());
        for (int i = 0; i < pcts.length; ++i) {
            final Percentile percentile = percentileList.get(i);
            assertThat(percentile.getPercent(), equalTo(pcts[i]));
            double value = percentile.getValue();
            double allowedError = value / Math.pow(10, numberSigDigits);
            assertThat(value, greaterThanOrEqualTo(minValue - allowedError));
            assertThat(value, lessThanOrEqualTo(maxValue + allowedError));

            if (percentile.getPercent() == 0) {
                assertThat(value, closeTo(minValue, allowedError));
            }
            if (percentile.getPercent() == 100) {
                assertThat(value, closeTo(maxValue, allowedError));
            }
        }

        for (int i = 1; i < percentileList.size(); ++i) {
            assertThat(percentileList.get(i).getValue(), greaterThanOrEqualTo(percentileList.get(i - 1).getValue()));
        }
    }

    @Override
    @Test
    public void testEmptyAggregation() throws Exception {
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo")
                                .field("value")
                                .interval(1l)
                                .minDocCount(0)
                                .subAggregation(
                                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR)
                                                .percentiles(10,
                                                15))).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2l));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        Histogram.Bucket bucket = histo.getBuckets().get(1);
        assertThat(bucket, notNullValue());

        Percentiles percentiles = bucket.getAggregations().get("percentiles");
        assertThat(percentiles, notNullValue());
        assertThat(percentiles.getName(), equalTo("percentiles"));
        assertThat(percentiles.percentile(10), equalTo(Double.NaN));
        assertThat(percentiles.percentile(15), equalTo(Double.NaN));
    }

    @Override
    @Test
    public void testUnmapped() throws Exception {
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("value")
                                .percentiles(0, 10, 15, 100)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(0l));

        Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertThat(percentiles, notNullValue());
        assertThat(percentiles.getName(), equalTo("percentiles"));
        assertThat(percentiles.percentile(0), equalTo(Double.NaN));
        assertThat(percentiles.percentile(10), equalTo(Double.NaN));
        assertThat(percentiles.percentile(15), equalTo(Double.NaN));
        assertThat(percentiles.percentile(100), equalTo(Double.NaN));
    }

    @Override
    @Test
    public void testSingleValuedField() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomIntBetween(1, 5);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("value")
                                .percentiles(pcts))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValue, maxValue, sigDigits);
    }

    @Override
    @Test
    public void testSingleValuedField_getProperty() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        global("global").subAggregation(
                                percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR)
                                        .field("value")
                                        .percentiles(pcts))).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        Global global = searchResponse.getAggregations().get("global");
        assertThat(global, notNullValue());
        assertThat(global.getName(), equalTo("global"));
        assertThat(global.getDocCount(), equalTo(10l));
        assertThat(global.getAggregations(), notNullValue());
        assertThat(global.getAggregations().asMap().size(), equalTo(1));

        Percentiles percentiles = global.getAggregations().get("percentiles");
        assertThat(percentiles, notNullValue());
        assertThat(percentiles.getName(), equalTo("percentiles"));
        assertThat((Percentiles) global.getProperty("percentiles"), sameInstance(percentiles));

    }

    @Override
    @Test
    public void testSingleValuedField_PartiallyUnmapped() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx", "idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("value")
                                .percentiles(pcts))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValue, maxValue, sigDigits);
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("value")
                                .script(new Script("_value - 1")).percentiles(pcts)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1, sigDigits);
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("value")
                                .script(new Script("_value - dec", ScriptType.INLINE, null, params)).percentiles(pcts)).execute()
                .actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1, sigDigits);
    }

    @Override
    @Test
    public void testMultiValuedField() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("values")
                                .percentiles(pcts))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValues, maxValues, sigDigits);
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("values")
                                .script(new Script("_value - 1")).percentiles(pcts)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1, sigDigits);
    }

    @Test
    public void testMultiValuedField_WithValueScript_Reverse() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("values")
                                .script(new Script("20 - _value")).percentiles(pcts)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, 20 - maxValues, 20 - minValues, sigDigits);
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR).field("values")
                                .script(new Script("_value - dec", ScriptType.INLINE, null, params)).percentiles(pcts)).execute()
                .actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1, sigDigits);
    }

    @Override
    @Test
    public void testScript_SingleValued() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR)
                                .script(new Script("doc['value'].value")).percentiles(pcts)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValue, maxValue, sigDigits);
    }

    @Override
    @Test
    public void testScript_SingleValued_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR)
                                .script(new Script("doc['value'].value - dec", ScriptType.INLINE, null, params)).percentiles(pcts))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1, sigDigits);
    }

    @Override
    @Test
    @AwaitsFix(bugUrl = "fails with -Dtests.seed=5BFFA768633A0A59 but only if run as a whole test class not if run as a single test method")
    public void testScript_ExplicitSingleValued_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR)
                                .script(new Script("doc['value'].value - dec", ScriptType.INLINE, null, params)).percentiles(pcts))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1, sigDigits);
    }

    @Override
    @Test
    public void testScript_MultiValued() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR)
                                .script(new Script("doc['values'].values")).percentiles(pcts)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValues, maxValues, sigDigits);
    }

    @Override
    @Test
    public void testScript_ExplicitMultiValued() throws Exception {
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles").numberOfSignificantValueDigits(sigDigits).method(PercentilesMethod.HDR)
                                .script(new Script("doc['values'].values")).percentiles(pcts)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValues, maxValues, sigDigits);
    }

    @Override
    @Test
    public void testScript_MultiValued_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercentiles();
        int sigDigits = randomSignificantDigits();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        percentiles("percentiles")
                                .numberOfSignificantValueDigits(sigDigits)
                                .method(PercentilesMethod.HDR)
                                .script(new Script(
                                        "List values = doc['values'].values; double[] res = new double[values.size()]; for (int i = 0; i < res.length; i++) { res[i] = values.get(i) - dec; }; return res;",
                                        ScriptType.INLINE, null, params)).percentiles(pcts)).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        final Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1, sigDigits);
    }

    @Test
    public void testOrderBySubAggregation() {
        int sigDigits = randomSignificantDigits();
        boolean asc = randomBoolean();
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo").field("value").interval(2l)
                                .subAggregation(
                                        percentiles("percentiles").method(PercentilesMethod.HDR).numberOfSignificantValueDigits(sigDigits)
                                                .percentiles(99))
                                .order(Order.aggregation("percentiles", "99", asc))).execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));

        Histogram histo = searchResponse.getAggregations().get("histo");
        double previous = asc ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (Histogram.Bucket bucket : histo.getBuckets()) {
            Percentiles percentiles = bucket.getAggregations().get("percentiles");
            double p99 = percentiles.percentile(99);
            if (asc) {
                assertThat(p99, greaterThanOrEqualTo(previous));
            } else {
                assertThat(p99, lessThanOrEqualTo(previous));
            }
            previous = p99;
        }
    }

}