package com.te.link.common.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: Talen
 * description: 消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageBody {

    long fromUserId;

    long timeStamp;

    long toId;

    short messageType;

    String content;
}
