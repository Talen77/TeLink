package com.te.link.core.diag;

import java.util.List;

public interface ModelClient {
    ModelTurn complete(List<DiagMessage> history) throws Exception;
}
