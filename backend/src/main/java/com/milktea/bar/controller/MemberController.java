package com.milktea.bar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.milktea.bar.common.ApiResponse;
import com.milktea.bar.dto.ContactChangeRequest;
import com.milktea.bar.dto.LegacyImportRequest;
import com.milktea.bar.dto.MemberCreateRequest;
import com.milktea.bar.dto.MemberProfileDetail;
import com.milktea.bar.dto.PointsAdjustRequest;
import com.milktea.bar.entity.Member;
import com.milktea.bar.service.MemberProfileService;
import com.milktea.bar.service.MemberService;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;
    private final MemberProfileService profileService;

    public MemberController(MemberService memberService, MemberProfileService profileService) {
        this.memberService = memberService;
        this.profileService = profileService;
    }

    /** 会员列表：可按等级、档案状态（有效 / 已合并）筛选 */
    @GetMapping
    public ApiResponse<List<Member>> list(@RequestParam(required = false) String level,
                                          @RequestParam(required = false) String status) {
        return ApiResponse.success(memberService.list(level, status));
    }

    /** 会员档案详情：合并归属、历史订单、积分流水、联系方式变更审计 */
    @GetMapping("/{id}/profile")
    public ApiResponse<MemberProfileDetail> profile(@PathVariable Long id) {
        return ApiResponse.success(profileService.detail(id));
    }

    @PostMapping
    public ApiResponse<Member> create(@RequestBody MemberCreateRequest req) {
        Member input = new Member();
        input.phone = req.phone;
        input.name = req.name;
        input.points = req.points;
        input.level = req.level;
        return ApiResponse.success(memberService.create(input, req.operator, req.operatorRole));
    }

    /** 仅支持改等级；手机/姓名/积分走各自的审计入口 */
    @PutMapping("/{id}")
    public ApiResponse<Member> update(@PathVariable Long id, @RequestBody Member input) {
        return ApiResponse.success(memberService.update(id, input));
    }

    /** 联系方式（手机号 / 姓名）变更：记录前后值、原因与生效时间 */
    @PostMapping("/{id}/contact-change")
    public ApiResponse<Member> changeContact(@PathVariable Long id,
                                             @RequestBody ContactChangeRequest req) {
        return ApiResponse.success(memberService.changeContact(id, req));
    }

    /** 积分手工调整：带积分流水，按最新版本校验 */
    @PostMapping("/{id}/points-adjust")
    public ApiResponse<Member> adjustPoints(@PathVariable Long id,
                                            @RequestBody PointsAdjustRequest req) {
        return ApiResponse.success(memberService.adjustPoints(id, req));
    }

    /** 负责人导入线下历史重复档案（可能同手机号），供合并冲突流程消重 */
    @PostMapping("/legacy-import")
    public ApiResponse<Member> legacyImport(@RequestBody LegacyImportRequest req) {
        return ApiResponse.success(memberService.importLegacy(req));
    }
}
