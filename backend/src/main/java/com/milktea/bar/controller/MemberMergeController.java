package com.milktea.bar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.milktea.bar.common.ApiResponse;
import com.milktea.bar.dto.MemberMergeDetail;
import com.milktea.bar.dto.MergeExecuteRequest;
import com.milktea.bar.dto.MergeMarkRequest;
import com.milktea.bar.dto.MergeResolveRequest;
import com.milktea.bar.dto.MergeRevokeRequest;
import com.milktea.bar.entity.MemberMergeRequest;
import com.milktea.bar.service.MergeService;

/**
 * 会员档案合并工单：
 * 待处理 / 冲突待处理 / 已合并 / 已撤销四态筛选，
 * 标记、预览（详情含两边手机号、姓名、积分、历史订单）、解决冲突、执行、撤销。
 */
@RestController
@RequestMapping("/api/member-merges")
public class MemberMergeController {

    private final MergeService mergeService;

    public MemberMergeController(MergeService mergeService) {
        this.mergeService = mergeService;
    }

    @GetMapping
    public ApiResponse<List<MemberMergeRequest>> list(
            @RequestParam(required = false) String status) {
        return ApiResponse.success(mergeService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<MemberMergeDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(mergeService.detail(id));
    }

    /** 标记重复会员为待合并（有手机号/积分冲突时直接落冲突待处理） */
    @PostMapping
    public ApiResponse<MemberMergeRequest> mark(@RequestBody MergeMarkRequest req) {
        return ApiResponse.success(mergeService.mark(req));
    }

    /** 负责人解决冲突（选择保留手机号、积分来源策略） */
    @PostMapping("/{id}/resolve")
    public ApiResponse<MemberMergeRequest> resolve(@PathVariable Long id,
                                                   @RequestBody MergeResolveRequest req) {
        return ApiResponse.success(mergeService.resolve(id, req));
    }

    /** 执行合并：历史订单与积分流水归主档，原档只读别名 */
    @PostMapping("/{id}/execute")
    public ApiResponse<MemberMergeRequest> execute(@PathVariable Long id,
                                                   @RequestBody MergeExecuteRequest req) {
        return ApiResponse.success(mergeService.execute(id, req));
    }

    /** 撤销合并（仅负责人，不重复计算积分地还原归属） */
    @PostMapping("/{id}/revoke")
    public ApiResponse<MemberMergeRequest> revoke(@PathVariable Long id,
                                                  @RequestBody MergeRevokeRequest req) {
        return ApiResponse.success(mergeService.revoke(id, req));
    }
}
