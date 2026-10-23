package com.example.es.impl;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.es.dto.SearchRequestDto;
import com.example.es.service.SearchService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class SearchServiceTest {

    @Autowired
    SearchService searchService;

    @Test
    void searchTest(){
        SearchRequestDto searchRequest = new SearchRequestDto("child_example")
                .keyword("transfer","title")
                .size(2);
        SearchResponse<ObjectNode> searchResp = searchService.search(searchRequest);
        Assertions.assertNotNull(searchResp);
        Assertions.assertTrue(searchResp.hits().total().value()>0);
        System.out.println("search size:"+searchResp.hits().total().value());
    }

}
