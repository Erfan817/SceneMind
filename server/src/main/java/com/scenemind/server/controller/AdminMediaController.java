package com.scenemind.server.controller;

import com.scenemind.server.common.ErrorCode;
import com.scenemind.server.common.Result;
import com.scenemind.server.dto.VideoContext;
import com.scenemind.server.exception.BusinessException;
import com.scenemind.server.service.AgentCheckpointService;
import com.scenemind.server.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理侧只读接口：导出视频上下文（ASR/OCR 片段），用于真实标注集采集与排障。
 *
 * <p>真实标注集需要「视频解析后的 segments + 人工标注的 query→时间戳」，
 * 这里把 Checkpoint 里的 {@link VideoContext} 原样导出，避免开发者手工翻库。</p>
 */
@RestController
@RequestMapping("/admin/media")
public class AdminMediaController {

    private final AgentCheckpointService checkpointService;
    private final AuthService authService;

    public AdminMediaController(AgentCheckpointService checkpointService, AuthService authService) {
        this.checkpointService = checkpointService;
        this.authService = authService;
    }

    @GetMapping("/{id}/context")
    public Result<VideoContext> context(
            @PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        authService.requireAdmin(userId);
        VideoContext context = checkpointService.loadContext(id);
        if (context == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该媒体还没有可导出的视频上下文");
        }
        return Result.ok(context);
    }
}
