package com.dfire.core.netty.worker.request;

import com.dfire.common.entity.HeraJobHistory;
import com.dfire.common.entity.model.HeraJobBean;
import com.dfire.common.entity.vo.HeraDebugHistoryVo;
import com.dfire.common.entity.vo.HeraJobHistoryVo;
import com.dfire.common.enums.StatusEnum;
import com.dfire.common.util.BeanConvertUtils;
import com.dfire.common.vo.JobStatus;
import com.dfire.core.config.HeraGlobalEnvironment;
import com.dfire.core.job.Job;
import com.dfire.core.job.JobContext;
import com.dfire.core.message.Protocol.*;
import com.dfire.core.netty.worker.WorkContext;
import com.dfire.core.util.JobUtils;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 下午4:20 2018/4/25
 * @desc worker job 最终执行体，收到master handler执行请求的时候，开始创建Job processor
 */
@Slf4j
public class WorkExecuteJob {

    public Future<Response> execute(final WorkContext workContext, final Request request) {
        if (request.getOperate() == Operate.Debug) {
            return debug(workContext, request);
        } else if (request.getOperate() == Operate.Manual) {
            return manual(workContext, request);
        } else if (request.getOperate() == Operate.Schedule) {
            return schedule(workContext, request);
        }
        return null;
    }


    /**
     * worker中，调度中心手动执行任务最终执行位置，JobUtils.createDebugJob创建job文件到服务器，拼接shell，并调用命令执行
     *
     * @param workContext
     * @param request
     * @return
     */


    private Future<Response> manual(WorkContext workContext, Request request) {
        ExecuteMessage message = null;
        StringBuilder filePath = new StringBuilder();

        try {
            message = ExecuteMessage.newBuilder().mergeFrom(request.getBody()).build();
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        final String historyId = message.getJobId();
        log.info("worker received master request to run manual job, historyId = {}", historyId);
        final HeraJobHistoryVo history = BeanConvertUtils.convert(workContext.getJobHistoryService().findById(historyId));

        Future<Response> future = workContext.getWorkThreadPool().submit(() -> {
            history.setExecuteHost(WorkContext.host);
            history.setStartTime(new Date());
            workContext.getJobHistoryService().update(BeanConvertUtils.convert(history));

            String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

            filePath.append(HeraGlobalEnvironment.getDownloadDir())
                    .append(File.separator)
                    .append(date)
                    .append(File.separator)
                    .append("manual-")
                    .append(historyId);
            File directory = new File(filePath.toString());
            if (!directory.exists()) {
                directory.mkdir();
            }
            HeraJobBean jobBean = workContext.getHeraGroupService().getUpstreamJobBean(history.getActionId());
            final Job job = JobUtils.createJob(new JobContext(JobContext.MANUAL_RUN),
                    jobBean, history, directory.getAbsolutePath(), workContext.getApplicationContext());
            workContext.getManualRunning().put(historyId, job);

            Integer exitCode = -1;
            Exception exception = null;
            try {
                exitCode = job.run();
            } catch (Exception e) {
                exception = e;
                history.getLog().appendHeraException(e);
            } finally {
                HeraJobHistoryVo heraJobHistoryVO = BeanConvertUtils.convert(workContext.getJobHistoryService().findById(history.getId()));
                heraJobHistoryVO.setEndTime(new Date());
                if (exitCode == 0) {
                    heraJobHistoryVO.setStatusEnum(StatusEnum.SUCCESS);
                } else {
                    heraJobHistoryVO.setStatusEnum(StatusEnum.FAILED);
                }
                workContext.getJobHistoryService().updateHeraJobHistoryStatus(BeanConvertUtils.convert(heraJobHistoryVO));

                HeraJobHistoryVo jobHistory = workContext.getManualRunning().get(historyId).getJobContext().getHeraJobHistory();
                workContext.getJobHistoryService().updateHeraJobHistoryLog(BeanConvertUtils.convert(jobHistory));

                workContext.getManualRunning().remove(historyId);
            }

            Status status = Status.OK;
            String errorText = "";
            if (exitCode != 0) {
                status = Status.ERROR;
            }
            if (exception != null) {
                errorText = exception.getMessage();
            }

            Response response = Response.newBuilder()
                    .setRid(request.getRid())
                    .setOperate(Operate.Manual)
                    .setStatus(status)
                    .setErrorText(errorText)
                    .build();
            log.info("send execute message, historyId = {}", historyId);
            return response;
        });

        return future;
    }

    /**
     * worker中，调度中心自动调度任务最终执行位置，JobUtils.createDebugJob创建job文件到服务器，拼接shell，并调用命令执行
     *
     * @param workContext
     * @param request
     * @return
     */

    private Future<Response> schedule(WorkContext workContext, Request request) {
        ExecuteMessage message = null;
        try {
            message = ExecuteMessage.newBuilder().mergeFrom(request.getBody()).build();
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        final String jobId = message.getJobId();
        log.info("worker received master request to run schedule, jobId :" + jobId);
        if (workContext.getRunning().containsKey(jobId)) {
            log.info("job is running, can not run again, jobId :" + jobId);
            return workContext.getWorkThreadPool().submit(new Callable<Response>() {
                @Override
                public Response call() throws Exception {
                    return Response.newBuilder()
                            .setRid(request.getRid())
                            .setOperate(Operate.Schedule)
                            .setStatus(Status.ERROR)
                            .build();
                }
            });
        }

        final JobStatus jobStatus = workContext.getHeraJobActionService().findJobStatus(jobId);
        final HeraJobHistory heraJobHistory = workContext.getJobHistoryService().findById(jobStatus.getHistoryId());
        HeraJobHistoryVo history = BeanConvertUtils.convert(heraJobHistory);
        Future<Response> future = workContext.getWorkThreadPool().submit(new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                history.setExecuteHost(WorkContext.host);
                history.setStartTime(new Date());
                workContext.getJobHistoryService().update(BeanConvertUtils.convert(history));

                HeraJobBean jobBean = workContext.getHeraGroupService().getUpstreamJobBean(history.getJobId());
                String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                File directory = new File(HeraGlobalEnvironment.getDownloadDir()
                        + File.separator + date + File.separator + history.getId());
                if (!directory.exists()) {
                    directory.mkdir();
                }

                final Job job = JobUtils.createJob(new JobContext(JobContext.SCHEDULE_RUN), jobBean, history, directory.getAbsolutePath(), workContext.getApplicationContext());
                workContext.getRunning().put(jobId, job);

                Integer exitCode = -1;
                Exception exception = null;
                try {
                    exitCode = job.run();
                } catch (Exception e) {
                    exception = e;
                    history.getLog().appendHeraException(e);
                } finally {
                    HeraJobHistoryVo heraJobHistory = BeanConvertUtils.convert(workContext.getJobHistoryService().findById(history.getId()));
                    heraJobHistory.setEndTime(new Date());
                    if (exitCode == 0) {
                        heraJobHistory.setStatusEnum(StatusEnum.SUCCESS);
                    } else {
                        heraJobHistory.setStatusEnum(StatusEnum.FAILED);
                    }
                    workContext.getJobHistoryService().updateHeraJobHistoryStatus(BeanConvertUtils.convert(heraJobHistory));
                    heraJobHistory.getLog().appendHera("exitCode=" + exitCode);

                    workContext.getJobHistoryService().update(BeanConvertUtils.convert(heraJobHistory));

                    workContext.getRunning().remove(jobId);
                }

                Status status = Status.OK;
                String errorText = "";
                if (exitCode != 0) {
                    status = Status.ERROR;
                }
                if (exception != null) {
                    errorText = exception.getMessage();
                }

                Response response = Response.newBuilder()
                        .setRid(request.getRid())
                        .setOperate(Operate.Schedule)
                        .setStatus(status)
                        .setErrorText(errorText)
                        .build();
                log.info("send execute message, jobId = " + jobId);
                return response;
            }
        });

        return future;

    }

    /**
     * worker中，开发中心脚本执行最终执行位置，JobUtils.createDebugJob创建job文件到服务器，拼接shell，并调用命令执行
     *
     * @param workContext
     * @param request
     * @return
     */
    private Future<Response> debug(WorkContext workContext, Request request) {
        DebugMessage debugMessage = null;
        try {
            debugMessage = DebugMessage.newBuilder().mergeFrom(request.getBody()).build();
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        String debugId = debugMessage.getDebugId();
        HeraDebugHistoryVo history = workContext.getDebugHistoryService().findById(debugId);
        Future<Response> future = workContext.getWorkThreadPool().submit(() -> {
            history.setExecuteHost(WorkContext.host);
            history.setStartTime(new Date());
            workContext.getDebugHistoryService().update(BeanConvertUtils.convert(history));

            String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            File directory = new File(HeraGlobalEnvironment.getDownloadDir() + File.separator + date + File.separator + "debug-" + debugId);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            Job job = JobUtils.createDebugJob(new JobContext(JobContext.DEBUG_RUN), BeanConvertUtils.convert(history),
                    directory.getAbsolutePath(), workContext.getApplicationContext());
            workContext.getDebugRunning().putIfAbsent(debugId, job);

            int exitCode = -1;
            Exception exception = null;
            try {
                exitCode = job.run();
            } catch (Exception e) {
                exception = e;
                history.getLog().appendHeraException(e);
            } finally {
                HeraDebugHistoryVo heraDebugHistoryVo = workContext.getDebugHistoryService().findById(debugId);
                heraDebugHistoryVo.setEndTime(new Date());
                if (exitCode == 0) {
                    heraDebugHistoryVo.setStatusEnum(StatusEnum.SUCCESS);
                } else {
                    heraDebugHistoryVo.setStatusEnum(StatusEnum.FAILED);
                }
                workContext.getDebugHistoryService().updateStatus(BeanConvertUtils.convert(heraDebugHistoryVo));
                HeraDebugHistoryVo debugHistory = workContext.getDebugRunning().get(debugId).getJobContext().getDebugHistory();
                workContext.getDebugHistoryService().updateLog(BeanConvertUtils.convert(debugHistory));
                workContext.getDebugRunning().remove(debugId);

            }
            Status status = Status.OK;
            String errorText = "";
            if (exitCode != 0) {
                status = Status.ERROR;
            }
            if (exception != null && exception.getMessage() != null) {
                errorText = exception.getMessage();
            }
            Response response = Response.newBuilder()
                    .setRid(request.getRid())
                    .setOperate(Operate.Debug)
                    .setStatus(status)
                    .setErrorText(errorText)
                    .build();
            return response;
        });
        return future;
    }

}
