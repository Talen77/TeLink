package com.te.link.common.domain.model;

import com.te.link.common.domain.protobuf.PacketHeader;
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
public class CompleteMessage {

    PacketHeader packetHeader;

    MessageBody messageBody;
}
