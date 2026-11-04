package com.tss.labeling.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    private static final Logger log =
            LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 每次请求由服务端生成编号，不直接信任客户端传入的值。
        String requestId = UUID.randomUUID().toString();

        // 保存到当前请求，供后续Controller读取。
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);

        // 响应头中也返回编号，便于浏览器或curl查看。
        response.setHeader("X-Request-Id", requestId);

        log.info("开始处理请求 requestId={} method={}",
                requestId, request.getMethod());

        filterChain.doFilter(request, response);
    }
}