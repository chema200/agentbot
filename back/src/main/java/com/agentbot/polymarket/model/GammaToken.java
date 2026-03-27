package com.agentbot.polymarket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GammaToken {
    @JsonProperty("token_id")
    private String tokenId;

    private String outcome;
    private String price;
    private String winner;
}
