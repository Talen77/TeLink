package com.te.link.core.diag;

import com.google.gson.JsonObject;

public interface DiagTool {
    String name();

    String call(JsonObject arguments);
}
