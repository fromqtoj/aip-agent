package com.aip.trace;

import org.springframework.stereotype.Component;

@Component
public class AgentTraceContextHolder {

    private final ThreadLocal<AgentTraceContext> contextHolder = new ThreadLocal<>();

    public void set(AgentTraceContext context) {
        contextHolder.set(context);
    }

    public AgentTraceContext get() {
        return contextHolder.get();
    }

    public void clear() {
        contextHolder.remove();
    }
}
