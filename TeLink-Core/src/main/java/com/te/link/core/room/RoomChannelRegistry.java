package com.te.link.core.room;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RoomChannelRegistry {
    static final AttributeKey<Long> ROOM_ID = AttributeKey.valueOf("telink.roomId");

    private final ConcurrentMap<Long, ChannelGroup> rooms = new ConcurrentHashMap<>();

    /**
     * @return 因为这次切换而在本机变空的旧房间；没有则返回 null
     */
    public Long join(long roomId, Channel channel) {
        Long previous = channel.attr(ROOM_ID).get();
        Long emptied = null;
        if (previous != null && previous != roomId && remove(previous, channel)) {
            emptied = previous;
        }
        rooms.computeIfAbsent(roomId, ignored -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)).add(channel);
        channel.attr(ROOM_ID).set(roomId);
        return emptied;
    }

    /**
     * @return 因为这条连接离开而在本机变空的房间；没有则返回 null
     */
    public Long leave(Channel channel) {
        Long roomId = channel.attr(ROOM_ID).getAndSet(null);
        if (roomId == null) {
            return null;
        }
        return remove(roomId, channel) ? roomId : null;
    }

    public boolean isJoined(long roomId, Channel channel) {
        Long current = channel.attr(ROOM_ID).get();
        return current != null && current == roomId;
    }

    public int broadcast(long roomId, String text) {
        ChannelGroup group = rooms.get(roomId);
        if (group == null || group.isEmpty()) {
            return 0;
        }
        int size = group.size();
        group.writeAndFlush(new TextWebSocketFrame(text));
        return size;
    }

    public Set<Long> roomIds() {
        return new HashSet<>(rooms.keySet());
    }

    public boolean isEmpty(long roomId) {
        ChannelGroup group = rooms.get(roomId);
        return group == null || group.isEmpty();
    }

    private boolean remove(long roomId, Channel channel) {
        ChannelGroup group = rooms.get(roomId);
        if (group == null) {
            return false;
        }
        group.remove(channel);
        if (group.isEmpty()) {
            rooms.remove(roomId, group);
            return true;
        }
        return false;
    }
}
