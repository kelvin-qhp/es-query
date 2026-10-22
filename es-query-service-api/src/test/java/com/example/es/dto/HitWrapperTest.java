package com.example.es.dto;

import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HitWrapperTest {

    @Test
    void exposesCollapseInnerHits() {
        Hit<String> hit = Hit.of(h -> h.id("o-1").index("orders").source("main")
                .innerHits("others", ihr -> ihr.hits(hm -> hm
                        .total(t -> t.value(1).relation(TotalHitsRelation.Eq))
                        .hits(hh -> hh.id("o-2").index("orders")))));
        HitWrapper<String> w = HitWrapper.from(hit);
        assertEquals(1, w.innerHits().get("others").hits().hits().size());
        assertEquals("o-2", w.innerHits().get("others").hits().hits().get(0).id());
    }

    @Test
    void noInnerHitsStaysNull() {
        HitWrapper<String> w = HitWrapper.from(Hit.of(h -> h.id("o-1").index("orders").source("main")));
        assertNull(w.innerHits());
    }
}
