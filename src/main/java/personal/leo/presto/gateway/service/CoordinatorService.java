package personal.leo.presto.gateway.service;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import personal.leo.presto.gateway.mapper.prestogateway.CoordinatorMapper;
import personal.leo.presto.gateway.mapper.prestogateway.po.CoordinatorPO;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TODO coordinator列表变更时,需要通知其它gateway实例
 * 本类不做coordinator状态管理,由{@link personal.leo.presto.gateway.schedule.CoordinatorHealthManager} 管理
 */
@Slf4j
@Service
public class CoordinatorService {
    @Getter
    final List<CoordinatorPO> coordinators = new CopyOnWriteArrayList<>();

    final AtomicInteger index = new AtomicInteger(0);


    @Autowired
    QueryService queryService;
    @Autowired
    CoordinatorMapper coordinatorMapper;

    @PostConstruct
    public void postConstruct() {
        reloadCoordinators();
    }


    public void reloadCoordinators() {
        final List<CoordinatorPO> activeCoordinators = coordinatorMapper.selectActiveCoordinators();
        this.coordinators.clear();
        if (CollectionUtils.isNotEmpty(activeCoordinators)) {
            this.coordinators.addAll(activeCoordinators);
        }
    }

    public List<CoordinatorPO> addCoordinator(String host, int port) {
        final boolean isActive = isActive(host, port);
        if (isActive) {
            final CoordinatorPO coordinator = CoordinatorPO.builder().host(host).port(port).active(isActive).build();
            final int count = coordinatorMapper.insert(coordinator);
            if (count > 0) {
                coordinators.add(coordinator);
            }
            return coordinators;
        } else {
            throw new RuntimeException("coordinator is inactived -> " + host + ":" + port);
        }
    }

    public boolean isActive(CoordinatorPO coordinator) {
        return isActive(coordinator.getHost(), coordinator.getPort());
    }

    public boolean isActive(String host, int port) {
        try {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpGet get = new HttpGet("http://" + host + ":" + port + "/v1/info");
                get.setConfig(
                        RequestConfig.custom()
                                .setSocketTimeout(1000)
                                .build()
                );

                try (CloseableHttpResponse resp = httpClient.execute(get)) {
                    if (Objects.equals(resp.getStatusLine().getStatusCode(), HttpStatus.SC_OK)) {
                        final String respBody = IOUtils.toString(resp.getEntity().getContent(), StandardCharsets.UTF_8);
                        /*
                            TODO 因presto新版本把statu放到了/ui/api/status里,访问status需要权限. 暂时不能直接访问,需要尝试增加用户信息才能获取到
                            这里使用 /v1/info,可以获取到coordinator启动成功的状态,但是gateway的请求在此时请求进来,请求会在等待资源的状态,等到该coordinator
                            所有的worker启动起来,才能继续执行任务
                         */
                        if (Objects.equals(JSON.parseObject(respBody).getBoolean("starting"), false)) {
//                            log.info(host + ":" + port + " isActive");
                            return true;
                        }
                    }

                }
            }
        } catch (Exception e) {
            log.info("isActive error: " + host + "_" + port, e.getMessage());
        }

        return false;
    }


    public String fetchCoordinatorUrl() {
        if (coordinators.isEmpty()) {
            throw new RuntimeException("No active coordinator");
        }

        final int index = this.index.get();
        final CoordinatorPO coordinator;
        if (index >= coordinators.size()) {
            coordinator = coordinators.get(0);
            this.index.compareAndSet(index, 0);
        } else {
            coordinator = coordinators.get(index);
            this.index.incrementAndGet();
        }

        return "http://" + coordinator.getHost() + ":" + coordinator.getPort();
    }

    public void removeCoordinator(String host, int port) {
        final CoordinatorPO coordinator = CoordinatorPO.builder().host(host).port(port).build();
        removeCoordinator(coordinator);
    }

    /**
     * 这里会把数据库里的coordinator信息也给删掉,谨慎使用
     *
     * @param coordinator
     */
    public void removeCoordinator(CoordinatorPO coordinator) {
        int count = coordinatorMapper.remove(coordinator);
        if (count > 0) {
            coordinators.remove(coordinator);
        } else {
            throw new RuntimeException("removeCoordinator failed: " + coordinator);
        }
    }


}
