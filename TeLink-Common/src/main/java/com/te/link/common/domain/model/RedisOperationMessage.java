package com.te.link.common.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: Talen
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RedisOperationMessage {

    String op;
    String key;
    String value;
    Integer expireSeconds;
}
