package com.dtp.core;

import cn.hutool.core.collection.CollUtil;
import com.dtp.common.VariableLinkedBlockingQueue;
import com.dtp.common.config.DtpProperties;
import com.dtp.common.config.ThreadPoolProperties;
import com.dtp.common.constant.DynamicTpConst;
import com.dtp.common.dto.DtpMainProp;
import com.dtp.common.em.NotifyTypeEnum;
import com.dtp.common.ex.DtpException;
import com.dtp.core.context.DtpContext;
import com.dtp.core.context.DtpContextHolder;
import com.dtp.core.convert.ExecutorConverter;
import com.dtp.core.handler.NotifierHandler;
import com.dtp.core.notify.AlarmLimiter;
import com.dtp.core.notify.NotifyHelper;
import com.dtp.core.reject.RejectHandlerGetter;
import com.dtp.core.support.ThreadPoolCreator;
import com.dtp.core.thread.DtpExecutor;
import com.github.dadiyang.equator.Equator;
import com.github.dadiyang.equator.FieldInfo;
import com.github.dadiyang.equator.GetterBaseEquator;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import lombok.var;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.dtp.common.dto.NotifyItem.getDefaultNotifyItems;
import static com.dtp.common.em.QueueTypeEnum.VARIABLE_LINKED_BLOCKING_QUEUE;

/**
 * Core Registry, which keeps all registered Dynamic ThreadPoolExecutors.
 *
 * @author: yanhom
 * @since 1.0.0
 **/
@Slf4j
public class DtpRegistry implements InitializingBean {

    private static final ExecutorService NOTIFY_EXECUTOR = ThreadPoolCreator.createWithTtl("dtp-notify");

    private static final Map<String, DtpExecutor> DTP_REGISTRY = new ConcurrentHashMap<>();

    private static final Equator EQUATOR = new GetterBaseEquator();

    private static DtpProperties dtpProperties;

    /**
     * Get all DtpExecutor names.
     * @return executor names
     */
    public static List<String> listAllDtpNames() {
        return Lists.newArrayList(DTP_REGISTRY.keySet());
    }

    /**
     * Register a Dynamic ThreadPoolExecutor.
     *
     * @param executor the newly created DtpExecutor instance
     * @param source the source of the call to register method
     */
    public static void register(DtpExecutor executor, String source) {
        log.info("DynamicTp register, source: {}, executor: {}", source, ExecutorConverter.convert(executor));
        DTP_REGISTRY.put(executor.getThreadPoolName(), executor);
    }

    /**
     * Get Dynamic ThreadPoolExecutor by thread pool name.
     *
     * @param name the name of dynamic thread pool
     * @return the managed DtpExecutor instance
     */
    public static DtpExecutor getExecutor(String name) {
        val executor= DTP_REGISTRY.get(name);
        if (Objects.isNull(executor)) {
            log.error("Cannot find a specified DynamicTp, name: {}", name);
            throw new DtpException("Cannot find a specified DynamicTp, name: " + name);
        }
        return executor;
    }

    /**
     * Refresh while the listening configuration changed.
     *
     * @param properties the main properties that maintain by config center
     */
    public static void refresh(DtpProperties properties) {
        if (Objects.isNull(properties) || CollUtil.isEmpty(properties.getExecutors())) {
            log.warn("DynamicTp refresh, empty threadPoolProperties.");
            return;
        }
        properties.getExecutors().forEach(x -> {
            if (StringUtils.isBlank(x.getThreadPoolName())) {
                log.warn("DynamicTp refresh, threadPoolName must not be empty.");
                return;
            }
            val dtpExecutor = DTP_REGISTRY.get(x.getThreadPoolName());
            if (Objects.isNull(dtpExecutor)) {
                log.warn("DynamicTp refresh, cannot find specified executor, name: {}.", x.getThreadPoolName());
                return;
            }
            refresh(dtpExecutor, x);
        });
    }

    private static void refresh(DtpExecutor executor, ThreadPoolProperties properties) {

        if (properties.getCorePoolSize() < 0 ||
                properties.getMaximumPoolSize() <= 0 ||
                properties.getMaximumPoolSize() < properties.getCorePoolSize() ||
                properties.getKeepAliveTime() < 0){
            log.error("DynamicTp refresh, invalid parameters exist, properties: {}", properties);
            return;
        }

        DtpMainProp oldProp = ExecutorConverter.convert(executor);
        doRefresh(executor, properties);
        DtpMainProp newProp = ExecutorConverter.convert(executor);
        if (oldProp.equals(newProp)) {
            log.warn("DynamicTp [{}] has no properties changed.", executor.getThreadPoolName());
            return;
        }

        List<FieldInfo> diffFields = EQUATOR.getDiffFields(oldProp, newProp);
        List<String> diffKeys = diffFields.stream().map(FieldInfo::getFieldName).collect(Collectors.toList());
        log.info("DynamicTp [{}] refreshed end, changed keys: {}, corePoolSize: [{}], maxPoolSize: [{}], " +
                        "queueType: [{}], queueCapacity: [{}], keepAliveTime: [{}], rejectedType: [{}], " +
                        "allowsCoreThreadTimeOut: [{}]",
                executor.getThreadPoolName(),
                diffKeys,
                String.format(DynamicTpConst.PROPERTIES_CHANGE_SHOW_STYLE, oldProp.getCorePoolSize(), newProp.getCorePoolSize()),
                String.format(DynamicTpConst.PROPERTIES_CHANGE_SHOW_STYLE, oldProp.getMaxPoolSize(), newProp.getMaxPoolSize()),
                String.format(DynamicTpConst.PROPERTIES_CHANGE_SHOW_STYLE, oldProp.getQueueType(), newProp.getQueueType()),
                String.format(DynamicTpConst.PROPERTIES_CHANGE_SHOW_STYLE, oldProp.getQueueCapacity(), newProp.getQueueCapacity()),
                String.format("%ss => %ss", oldProp.getKeepAliveTime(), newProp.getKeepAliveTime()),
                String.format(DynamicTpConst.PROPERTIES_CHANGE_SHOW_STYLE, oldProp.getRejectType(), newProp.getRejectType()),
                String.format(DynamicTpConst.PROPERTIES_CHANGE_SHOW_STYLE, oldProp.isAllowCoreThreadTimeOut(),
                        newProp.isAllowCoreThreadTimeOut()));

        val notifyItem = NotifyHelper.getNotifyItem(executor, NotifyTypeEnum.CHANGE);
        boolean ifNotice = CollUtil.isNotEmpty(dtpProperties.getPlatforms()) &&
                Objects.nonNull(notifyItem) &&
                notifyItem.isEnabled();
        if (!ifNotice) {
            return;
        }
        DtpContext context = DtpContext.builder()
                .dtpExecutor(executor)
                .platforms(dtpProperties.getPlatforms())
                .notifyItem(notifyItem)
                .build();
        DtpContextHolder.set(context);
        NOTIFY_EXECUTOR.execute(() -> NotifierHandler.getInstance().sendNotice(oldProp, diffKeys));
    }

    private static void doRefresh(DtpExecutor dtpExecutor, ThreadPoolProperties properties) {

        if (!Objects.equals(dtpExecutor.getCorePoolSize(), properties.getCorePoolSize())) {
            dtpExecutor.setCorePoolSize(properties.getCorePoolSize());
        }

        if (!Objects.equals(dtpExecutor.getMaximumPoolSize(), properties.getMaximumPoolSize())) {
            dtpExecutor.setMaximumPoolSize(properties.getMaximumPoolSize());
        }

        if (!Objects.equals(dtpExecutor.getKeepAliveTime(properties.getUnit()), properties.getKeepAliveTime())) {
            dtpExecutor.setKeepAliveTime(properties.getKeepAliveTime(), properties.getUnit());
        }

        if (!Objects.equals(dtpExecutor.allowsCoreThreadTimeOut(), properties.isAllowCoreThreadTimeOut())) {
            dtpExecutor.allowCoreThreadTimeOut(properties.isAllowCoreThreadTimeOut());
        }

        // update reject handler
        if (!Objects.equals(dtpExecutor.getRejectHandlerName(), properties.getRejectedHandlerType())) {
            dtpExecutor.setRejectedExecutionHandler(RejectHandlerGetter.getProxy(properties.getRejectedHandlerType()));
            dtpExecutor.setRejectHandlerName(properties.getRejectedHandlerType());
        }

        // update work queue capacity
        if (!Objects.equals(dtpExecutor.getQueueCapacity(), properties.getQueueCapacity()) &&
                Objects.equals(properties.getQueueType(), VARIABLE_LINKED_BLOCKING_QUEUE.getName())) {
            val blockingQueue = dtpExecutor.getQueue();
            if (blockingQueue instanceof VariableLinkedBlockingQueue) {
                ((VariableLinkedBlockingQueue<Runnable>)blockingQueue).setCapacity(properties.getQueueCapacity());
            } else {
                log.error("DynamicTp refresh, the blockingqueue capacity cannot be reset, dtpName: {}, queueType {}",
                        dtpExecutor.getThreadPoolName(), dtpExecutor.getQueueName());
            }
        }
        dtpExecutor.setWaitForTasksToCompleteOnShutdown(properties.isWaitForTasksToCompleteOnShutdown());
        dtpExecutor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());

        if (CollUtil.isEmpty(properties.getNotifyItems())) {
            dtpExecutor.setNotifyItems(getDefaultNotifyItems());
            return;
        }
        NotifyHelper.setExecutorNotifyItems(dtpExecutor, dtpProperties, properties);
        dtpExecutor.setNotifyItems(properties.getNotifyItems());
    }

    @Autowired
    public void setDtpProperties(DtpProperties dtpProperties) {
        DtpRegistry.dtpProperties = dtpProperties;
    }

    @Override
    public void afterPropertiesSet() {

        if (CollectionUtils.isEmpty(dtpProperties.getExecutors())) {
            return;
        }

        // It is better to define one spring bean using @Bean for each thread pool declared in configuration.
        // Mainly using the advantage of spring bean lifecycle.
        dtpProperties.getExecutors().forEach(x -> {
            var executor = DTP_REGISTRY.get(x.getThreadPoolName());
            if (Objects.nonNull(executor)) {
                doRefresh(executor, x);
                return;
            }
            log.warn("Please define the threadPool {} using @Bean first.", x.getThreadPoolName());
        });

        DTP_REGISTRY.forEach((k, v) -> {
            NotifyHelper.fillNotifyItems(dtpProperties.getPlatforms(), v.getNotifyItems());
            v.getNotifyItems().forEach(x -> AlarmLimiter.initAlarmLimiter(k, x));
        });
    }
}
