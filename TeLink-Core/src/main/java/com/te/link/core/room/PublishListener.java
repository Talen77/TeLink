package com.te.link.core.room;

public interface PublishListener {
    void onSuccess();

    void onFailure(Throwable error);
}
