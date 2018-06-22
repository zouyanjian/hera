package com.dfire.controller;

import com.alibaba.fastjson.JSONObject;
import com.dfire.common.entity.*;
import com.dfire.common.entity.vo.HeraGroupVo;
import com.dfire.common.entity.vo.HeraJobTreeNodeVo;
import com.dfire.common.entity.vo.HeraJobVo;
import com.dfire.common.enums.HttpCode;
import com.dfire.common.enums.StatusEnum;
import com.dfire.common.enums.TriggerTypeEnum;
import com.dfire.common.service.HeraGroupService;
import com.dfire.common.service.HeraJobActionService;
import com.dfire.common.service.HeraJobHistoryService;
import com.dfire.common.service.HeraJobService;
import com.dfire.common.util.BeanConvertUtils;
import com.dfire.common.vo.RestfulResponse;
import com.dfire.config.WebSecurityConfig;
import com.dfire.core.message.Protocol.ExecuteKind;
import com.dfire.core.netty.worker.WorkClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.WebAsyncTask;

import javax.servlet.http.HttpSession;
import java.util.List;

/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 16:50 2018/1/13
 * @desc 调度中心视图管理器
 */
@Controller
@RequestMapping("/scheduleCenter")
public class ScheduleCenterController {

    @Autowired
    HeraJobService heraJobService;
    @Autowired
    HeraJobActionService heraJobActionService;
    @Autowired
    HeraGroupService heraGroupService;
    @Autowired
    HeraJobHistoryService heraJobHistoryService;

    @Autowired
    WorkClient workClient;


    @RequestMapping()
    public String login() {
        return "scheduleCenter/scheduleCenter.index";
    }

    @RequestMapping(value = "/init", method = RequestMethod.POST)
    @ResponseBody
    public List<HeraJobTreeNodeVo> initJobTree(HttpSession session) {
        List<HeraJobTreeNodeVo> list = heraJobService.buildJobTree();
        HeraUser user = (HeraUser) session.getAttribute(WebSecurityConfig.SESSION_KEY);
        if (user != null) {
            String name = user.getName();

        }
        return list;
    }

    @RequestMapping(value = "/getJobMessage", method = RequestMethod.GET)
    @ResponseBody
    public HeraJobVo getJobMessage(Integer jobId) {
        HeraJob job = heraJobService.findById(jobId);
        HeraJobVo heraJobVo = BeanConvertUtils.convert(job);
        return heraJobVo;
    }

    @RequestMapping(value = "/getGroupMessage", method = RequestMethod.GET)
    @ResponseBody
    public HeraGroupVo getGroupMessage(Integer groupId) {
        HeraGroup group = heraGroupService.findById(groupId);
        HeraGroupVo heraGroupVo = BeanConvertUtils.convert(group);
        return heraGroupVo;
    }

    /**
     * 手动执行任务
     *
     * @param actionId
     * @return
     */
    @RequestMapping(value = "/manual", method = RequestMethod.GET)
    @ResponseBody
    public WebAsyncTask<String> manual(String actionId, Integer triggerType) {
        actionId = "201806080000000027";
        triggerType = 1;
        ExecuteKind kind = null;
        TriggerTypeEnum triggerTypeEnum = null;
        if (triggerType == 1) {
            kind = ExecuteKind.ManualKind;
            triggerTypeEnum = TriggerTypeEnum.MANUAL;
        } else if (triggerType == 2) {
            kind = ExecuteKind.ScheduleKind;
            triggerTypeEnum = TriggerTypeEnum.MANUAL_RECOVER;
        }
        //todo 权限判定

        HeraAction heraAction = heraJobActionService.findById(actionId);

        HeraJobHistory actionHistory = HeraJobHistory.builder().build();
        actionHistory.setJobId(heraAction.getJobId());
        actionHistory.setActionId(heraAction.getId());
        actionHistory.setTriggerType(triggerTypeEnum.getId());
        actionHistory.setOperator(heraAction.getOwner());
        actionHistory.setIllustrate("触发人pjx");
        actionHistory.setStatus(StatusEnum.RUNNING.toString());
        actionHistory.setStatisticEndTime(heraAction.getStatisticEndTime());
        actionHistory.setHostGroupId(heraAction.getHistoryId());
        actionHistory.setProperties("{}");
        heraJobHistoryService.insert(actionHistory);


        return new WebAsyncTask<>(3000, () -> {
            try {

                workClient.executeJobFromWeb(ExecuteKind.ManualKind, actionHistory.getId());
            } catch (Exception e) {

            }
            return "";
        });
    }

    @RequestMapping(value = "/getJobVersion", method = RequestMethod.GET)
    @ResponseBody
    public HeraAction getJobVersion(String jobId) {
        HeraAction list = heraJobActionService.findByJobId(jobId);
        return list;

    }

    @RequestMapping(value = "/updateJobMessage", method = RequestMethod.POST)
    @ResponseBody
    public boolean updateJobMessage(HeraJobVo heraJobVo) {
        HeraJob heraJob = BeanConvertUtils.convertToHeraJob(heraJobVo);
        return heraJobService.update(heraJob) > 0;
    }

    @RequestMapping(value = "/updateGroupMessage", method = RequestMethod.POST)
    @ResponseBody
    public boolean updateGroupMessage(HeraGroupVo groupVo) {
        HeraGroup heraGroup = BeanConvertUtils.convert(groupVo);
        System.out.println(JSONObject.toJSONString(heraGroup));
        return heraGroupService.update(heraGroup) > 0;
    }

    @RequestMapping(value = "/deleteJob", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteJob(Integer id, Boolean isGroup) {
        if (isGroup) {
            return heraGroupService.delete(id) > 0;
        }
        return  heraJobService.delete(id) > 0;
    }
    @RequestMapping(value = "/addJob", method = RequestMethod.POST)
    @ResponseBody
    public RestfulResponse addJob(HeraJob heraJob, HttpSession session) {
        Object attribute = session.getAttribute(WebSecurityConfig.SESSION_KEY);
        if (attribute == null) {
            return new RestfulResponse(HttpCode.USER_NOT_LOGIN);
        }
        HeraUser user = (HeraUser) attribute;
        heraJob.setOwner(user.getName());
        return new RestfulResponse(heraJobService.insert(heraJob) > 0 ? HttpCode.REQUEST_SUCCESS : HttpCode.REQUEST_FAIL);
    }

    @RequestMapping(value = "/changeSwitch", method = RequestMethod.POST)
    @ResponseBody
    public RestfulResponse changeSwitch(Integer id) {
        return new RestfulResponse(heraJobService.changeSwitch(id) ? HttpCode.REQUEST_SUCCESS : HttpCode.REQUEST_FAIL);
    }


}
