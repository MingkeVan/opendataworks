package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Doris 集群配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisClusterService {

    private final DorisClusterMapper dorisClusterMapper;

    /**
     * 查询所有集群
     */
    public List<DorisCluster> listAll() {
        return dorisClusterMapper.selectList(
            new LambdaQueryWrapper<DorisCluster>()
                .orderByDesc(DorisCluster::getIsDefault)
                .orderByAsc(DorisCluster::getClusterName)
        );
    }

    /**
     * 根据ID获取集群
     */
    public DorisCluster getById(Long id) {
        return dorisClusterMapper.selectById(id);
    }

    /**
     * 创建集群
     */
    @Transactional
    public DorisCluster create(DorisCluster cluster) {
        validate(cluster, true);
        handleDefaultFlag(cluster, null);
        dorisClusterMapper.insert(cluster);
        log.info("Created Doris cluster: {}", cluster.getClusterName());
        return cluster;
    }

    /**
     * 更新集群
     */
    @Transactional
    public DorisCluster update(Long id, DorisCluster cluster) {
        DorisCluster exists = dorisClusterMapper.selectById(id);
        if (exists == null) {
            throw new RuntimeException("Doris 集群不存在");
        }
        if (!StringUtils.hasText(cluster.getPassword())) {
            cluster.setPassword(exists.getPassword());
        }
        validate(cluster, false);
        cluster.setId(id);
        handleDefaultFlag(cluster, id);
        dorisClusterMapper.updateById(cluster);
        log.info("Updated Doris cluster: {}", cluster.getClusterName());
        return dorisClusterMapper.selectById(id);
    }

    /**
     * 删除集群
     */
    @Transactional
    public void delete(Long id) {
        dorisClusterMapper.deleteById(id);
        log.info("Deleted Doris cluster: {}", id);
    }

    /**
     * 设置默认集群
     */
    @Transactional
    public void setDefault(Long id) {
        DorisCluster exists = dorisClusterMapper.selectById(id);
        if (exists == null) {
            throw new RuntimeException("Doris 集群不存在");
        }

        resetDefaultFlag(id);

        exists.setIsDefault(1);
        dorisClusterMapper.updateById(exists);
        log.info("Set default Doris cluster: {}", id);
    }

    private void validate(DorisCluster cluster, boolean requirePassword) {
        if (cluster == null) {
            throw new IllegalArgumentException("Doris 集群不能为空");
        }
        if (!StringUtils.hasText(cluster.getClusterName())) {
            throw new RuntimeException("集群名称不能为空");
        }
        if (!StringUtils.hasText(cluster.getFeHost())) {
            throw new RuntimeException("FE Host 不能为空");
        }
        if (cluster.getFePort() == null) {
            throw new RuntimeException("FE Port 不能为空");
        }
        if (!StringUtils.hasText(cluster.getUsername())) {
            throw new RuntimeException("用户名不能为空");
        }
        if (requirePassword && !StringUtils.hasText(cluster.getPassword())) {
            throw new RuntimeException("密码不能为空");
        }
        if (cluster.getIsDefault() == null) {
            cluster.setIsDefault(0);
        }
        if (!StringUtils.hasText(cluster.getStatus())) {
            cluster.setStatus("active");
        }
    }

    private void handleDefaultFlag(DorisCluster cluster, Long currentId) {
        if (cluster.getIsDefault() != null && cluster.getIsDefault() == 1) {
            resetDefaultFlag(currentId);
        }
    }

    private void resetDefaultFlag(Long excludeId) {
        LambdaUpdateWrapper<DorisCluster> updateWrapper = new LambdaUpdateWrapper<DorisCluster>()
            .set(DorisCluster::getIsDefault, 0);
        if (excludeId != null) {
            updateWrapper.ne(DorisCluster::getId, excludeId);
        }
        dorisClusterMapper.update(null, updateWrapper);
    }
}
