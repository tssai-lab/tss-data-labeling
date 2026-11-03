package com.tss.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.service.NativeDownloadTicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;

@RestController
@RequestMapping("/api/download-tickets")
public class NativeDownloadTicketController {

    private final NativeDownloadTicketService ticketService;

    public NativeDownloadTicketController(NativeDownloadTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    public ApiResponse<TicketResponse> issue(@Valid @RequestBody TicketRequest request) {
        try {
            Object tokenValue = StpUtil.getTokenValue();
            NativeDownloadTicketService.IssuedTicket issued = ticketService.issue(
                    request.target(),
                    tokenValue == null ? null : String.valueOf(tokenValue)
            );
            return ApiResponse.ok(new TicketResponse(issued.downloadUrl(), issued.expiresAt()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下载目标无效", exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "待下载请求过多，请稍后重试", exception);
        }
    }

    public record TicketRequest(@NotBlank String target) {
    }

    public record TicketResponse(String downloadUrl, Instant expiresAt) {
    }
}
