#!/usr/bin/env python3
"""
集成测试: Java -> Python Service -> DolphinScheduler
测试完整的工作流管理流程:
1. 创建空白工作流
2. 添加节点
3. 添加节点依赖
4. 设置定时调度
5. 上线流程
"""

import json
import logging
import sys
import time
from typing import Dict, List, Optional

import requests

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
logger = logging.getLogger(__name__)


class IntegrationTest:
    """集成测试类"""

    def __init__(self, python_service_url: str = "http://localhost:8081"):
        self.python_service_url = python_service_url
        self.workflow_code: Optional[int] = None
        self.task_codes: List[int] = []

    def check_health(self) -> bool:
        """检查Python服务健康状态"""
        try:
            response = requests.get(f"{self.python_service_url}/health", timeout=5)
            if response.status_code == 200:
                logger.info("✓ Python服务健康检查通过")
                return True
            else:
                logger.error(f"✗ Python服务健康检查失败: {response.status_code}")
                return False
        except Exception as e:
            logger.error(f"✗ 无法连接到Python服务: {e}")
            return False

    def _create_initial_workflow(self) -> bool:
        """创建初始工作流(直接使用sync,DolphinScheduler不支持空工作流)"""
        # DolphinScheduler要求工作流至少有一个任务
        # 我们直接用sync创建工作流并添加第一个任务
        # sync接口会自动创建工作流如果不存在

        base_code = int(time.time() * 1000)
        placeholder_task_code = base_code

        sync_payload = {
            "workflowName": "integration-test-workflow",
            "projectName": "data-portal",
            "tenantCode": "default",
            "executionType": "PARALLEL",
            "workerGroup": "default",
            "description": "集成测试工作流",
            "tasks": [{
                "code": placeholder_task_code,
                "name": "placeholder_task",
                "version": 1,
                "description": "占位任务",
                "taskParams": {
                    "rawScript": "#!/bin/bash\necho 'Placeholder task'"
                },
                "taskPriority": "MEDIUM",
                "workerGroup": "default",
                "failRetryTimes": 0,
                "failRetryInterval": 1,
                "timeout": 60,
                "timeoutFlag": "OPEN",
                "flag": "YES"
            }],
            "relations": [],
            "locations": [{"taskCode": placeholder_task_code, "x": 220, "y": 140}]
        }

        try:
            # 直接使用sync,它会创建工作流如果不存在
            # 这里我们使用临时的workflow_code = 0,sync应该自动创建
            # 但实际上我们需要先从响应中获取workflow_code

            # 方案: 使用一个假的 workflow_code, Python服务应该会创建它
            temp_workflow_code = int(time.time() * 1000)

            response = requests.post(
                f"{self.python_service_url}/api/v1/workflows/{temp_workflow_code}/sync",
                json=sync_payload,
                timeout=10
            )
            response.raise_for_status()
            result = response.json()

            if result.get("success"):
                data = result.get("data", {})
                self.workflow_code = data.get("workflowCode")
                logger.info(f"  ✓ 创建工作流成功, workflowCode: {self.workflow_code}")
                return True
            else:
                logger.error(f"  ✗ 创建工作流失败: {result.get('message')}")
                return False

        except Exception as e:
            logger.error(f"  ✗ 创建工作流异常: {e}")
            return False

    def test_ensure_workflow(self, workflow_name: str = "integration-test-workflow") -> bool:
        """测试1: 创建空白工作流"""
        logger.info("=" * 60)
        logger.info("测试1: 创建空白工作流")
        logger.info("=" * 60)

        payload = {
            "workflowName": workflow_name,
            "projectName": "data-portal",
            "tenantCode": "default",
            "executionType": "PARALLEL",
            "workerGroup": "default",
            "description": "集成测试工作流"
        }

        try:
            response = requests.post(
                f"{self.python_service_url}/api/v1/workflows/ensure",
                json=payload,
                timeout=10
            )
            response.raise_for_status()
            result = response.json()

            if result.get("success"):
                data = result.get("data", {})
                self.workflow_code = data.get("workflowCode")
                logger.info(f"✓ 成功创建工作流, workflowCode: {self.workflow_code}")
                logger.info(f"  - 是否新创建: {data.get('created', False)}")
                return True
            else:
                logger.error(f"✗ 创建工作流失败: {result.get('message')}")
                return False

        except Exception as e:
            logger.error(f"✗ 创建工作流异常: {e}")
            return False

    def test_sync_workflow_with_nodes(self) -> bool:
        """测试2: 添加节点和节点依赖"""
        logger.info("=" * 60)
        logger.info("测试2: 添加节点和节点依赖")
        logger.info("=" * 60)

        # 如果没有workflow code,先创建一个空工作流
        if not self.workflow_code:
            logger.info("  - 首先创建工作流...")
            if not self._create_initial_workflow():
                return False

        # 生成任务代码
        base_code = int(time.time() * 1000)
        task1_code = base_code + 1
        task2_code = base_code + 2
        task3_code = base_code + 3
        self.task_codes = [task1_code, task2_code, task3_code]

        # 定义3个任务
        tasks = [
            {
                "code": task1_code,
                "name": "task_extract_data",
                "version": 1,
                "description": "数据提取任务",
                "taskParams": {
                    "rawScript": "#!/bin/bash\necho 'Extracting data...'\nsleep 2\necho 'Data extracted successfully'"
                },
                "taskPriority": "HIGH",
                "workerGroup": "default",
                "failRetryTimes": 2,
                "failRetryInterval": 1,
                "timeout": 300,
                "timeoutFlag": "OPEN",
                "flag": "YES"
            },
            {
                "code": task2_code,
                "name": "task_transform_data",
                "version": 1,
                "description": "数据转换任务",
                "taskParams": {
                    "rawScript": "#!/bin/bash\necho 'Transforming data...'\nsleep 3\necho 'Data transformed successfully'"
                },
                "taskPriority": "MEDIUM",
                "workerGroup": "default",
                "failRetryTimes": 1,
                "failRetryInterval": 1,
                "timeout": 300,
                "timeoutFlag": "OPEN",
                "flag": "YES"
            },
            {
                "code": task3_code,
                "name": "task_load_data",
                "version": 1,
                "description": "数据加载任务",
                "taskParams": {
                    "rawScript": "#!/bin/bash\necho 'Loading data...'\nsleep 2\necho 'Data loaded successfully'"
                },
                "taskPriority": "MEDIUM",
                "workerGroup": "default",
                "failRetryTimes": 1,
                "failRetryInterval": 1,
                "timeout": 300,
                "timeoutFlag": "OPEN",
                "flag": "YES"
            }
        ]

        # 定义任务依赖关系: task1 -> task2 -> task3
        relations = [
            {
                "preTaskCode": task1_code,
                "postTaskCode": task2_code
            },
            {
                "preTaskCode": task2_code,
                "postTaskCode": task3_code
            }
        ]

        # 定义任务位置(用于DAG可视化)
        locations = [
            {"taskCode": task1_code, "x": 220, "y": 140},
            {"taskCode": task2_code, "x": 400, "y": 140},
            {"taskCode": task3_code, "x": 580, "y": 140}
        ]

        payload = {
            "workflowName": "integration-test-workflow",
            "projectName": "data-portal",
            "tenantCode": "default",
            "executionType": "PARALLEL",
            "workerGroup": "default",
            "tasks": tasks,
            "relations": relations,
            "locations": locations
        }

        try:
            response = requests.post(
                f"{self.python_service_url}/api/v1/workflows/{self.workflow_code}/sync",
                json=payload,
                timeout=15
            )
            response.raise_for_status()
            result = response.json()

            if result.get("success"):
                data = result.get("data", {})
                task_count = data.get("taskCount", 0)
                logger.info(f"✓ 成功同步工作流节点")
                logger.info(f"  - 任务数量: {task_count}")
                logger.info(f"  - 任务代码: {self.task_codes}")
                logger.info(f"  - 依赖关系: task1 -> task2 -> task3")
                return True
            else:
                logger.error(f"✗ 同步工作流失败: {result.get('message')}")
                return False

        except Exception as e:
            logger.error(f"✗ 同步工作流异常: {e}")
            return False

    def test_release_workflow_online(self) -> bool:
        """测试3: 上线工作流"""
        logger.info("=" * 60)
        logger.info("测试3: 上线工作流")
        logger.info("=" * 60)

        if not self.workflow_code:
            logger.error("✗ workflowCode不存在,请先创建工作流")
            return False

        payload = {
            "projectName": "data-portal",
            "workflowName": "integration-test-workflow",
            "releaseState": "ONLINE"
        }

        try:
            response = requests.post(
                f"{self.python_service_url}/api/v1/workflows/{self.workflow_code}/release",
                json=payload,
                timeout=10
            )
            response.raise_for_status()
            result = response.json()

            if result.get("success"):
                logger.info(f"✓ 成功上线工作流")
                logger.info(f"  - workflowCode: {self.workflow_code}")
                logger.info(f"  - 状态: ONLINE")
                return True
            else:
                logger.error(f"✗ 上线工作流失败: {result.get('message')}")
                return False

        except Exception as e:
            logger.error(f"✗ 上线工作流异常: {e}")
            return False

    def test_start_workflow(self) -> bool:
        """测试4: 启动工作流"""
        logger.info("=" * 60)
        logger.info("测试4: 启动工作流")
        logger.info("=" * 60)

        if not self.workflow_code:
            logger.error("✗ workflowCode不存在,请先创建工作流")
            return False

        payload = {
            "projectName": "data-portal",
            "workflowName": "integration-test-workflow",
            "workerGroup": "default"
        }

        try:
            response = requests.post(
                f"{self.python_service_url}/api/v1/workflows/{self.workflow_code}/start",
                json=payload,
                timeout=10
            )
            response.raise_for_status()
            result = response.json()

            if result.get("success"):
                data = result.get("data", {})
                instance_id = data.get("instanceId", "N/A")
                logger.info(f"✓ 成功启动工作流")
                logger.info(f"  - workflowCode: {self.workflow_code}")
                logger.info(f"  - instanceId: {instance_id}")
                logger.info(f"  - 消息: {data.get('message', '')}")
                return True
            else:
                logger.error(f"✗ 启动工作流失败: {result.get('message')}")
                return False

        except Exception as e:
            logger.error(f"✗ 启动工作流异常: {e}")
            return False

    def test_release_workflow_offline(self) -> bool:
        """测试5: 下线工作流"""
        logger.info("=" * 60)
        logger.info("测试5: 下线工作流")
        logger.info("=" * 60)

        if not self.workflow_code:
            logger.error("✗ workflowCode不存在,请先创建工作流")
            return False

        payload = {
            "projectName": "data-portal",
            "workflowName": "integration-test-workflow",
            "releaseState": "OFFLINE"
        }

        try:
            response = requests.post(
                f"{self.python_service_url}/api/v1/workflows/{self.workflow_code}/release",
                json=payload,
                timeout=10
            )
            response.raise_for_status()
            result = response.json()

            if result.get("success"):
                logger.info(f"✓ 成功下线工作流")
                logger.info(f"  - workflowCode: {self.workflow_code}")
                logger.info(f"  - 状态: OFFLINE")
                return True
            else:
                logger.error(f"✗ 下线工作流失败: {result.get('message')}")
                return False

        except Exception as e:
            logger.error(f"✗ 下线工作流异常: {e}")
            return False

    def run_all_tests(self) -> bool:
        """运行所有集成测试"""
        logger.info("\n" + "=" * 60)
        logger.info("开始运行集成测试")
        logger.info("=" * 60 + "\n")

        test_results = []

        # 1. 健康检查
        if not self.check_health():
            logger.error("\n" + "=" * 60)
            logger.error("集成测试失败: Python服务不可用")
            logger.error("=" * 60)
            return False

        # 2. 创建空白工作流 (跳过 - DolphinScheduler需要至少一个任务才能创建工作流)
        logger.info("=" * 60)
        logger.info("测试1: 创建带节点的工作流 (DolphinScheduler要求至少一个任务)")
        logger.info("=" * 60)

        # 直接跳到添加节点和依赖,这会同时创建工作流
        result = self.test_sync_workflow_with_nodes()
        test_results.append(("创建工作流并添加节点", result))
        if not result:
            self.print_summary(test_results)
            return False

        # 等待一下
        time.sleep(1)

        # 4. 上线工作流
        result = self.test_release_workflow_online()
        test_results.append(("上线工作流", result))
        if not result:
            self.print_summary(test_results)
            return False

        # 等待一下
        time.sleep(1)

        # 5. 启动工作流
        result = self.test_start_workflow()
        test_results.append(("启动工作流", result))

        # 等待一下
        time.sleep(1)

        # 6. 下线工作流
        result = self.test_release_workflow_offline()
        test_results.append(("下线工作流", result))

        # 打印测试总结
        self.print_summary(test_results)

        return all(result for _, result in test_results)

    def print_summary(self, test_results: List[tuple]):
        """打印测试总结"""
        logger.info("\n" + "=" * 60)
        logger.info("测试总结")
        logger.info("=" * 60)

        passed = sum(1 for _, result in test_results if result)
        total = len(test_results)

        for test_name, result in test_results:
            status = "✓ PASS" if result else "✗ FAIL"
            logger.info(f"{status} - {test_name}")

        logger.info("-" * 60)
        logger.info(f"总计: {passed}/{total} 测试通过")

        if self.workflow_code:
            logger.info(f"工作流代码: {self.workflow_code}")
        if self.task_codes:
            logger.info(f"任务代码: {self.task_codes}")

        logger.info("=" * 60 + "\n")


def main():
    """主函数"""
    import argparse

    parser = argparse.ArgumentParser(description="集成测试: Java -> Python Service -> DolphinScheduler")
    parser.add_argument(
        "--service-url",
        default="http://localhost:8081",
        help="Python服务URL (默认: http://localhost:8081)"
    )
    args = parser.parse_args()

    test = IntegrationTest(python_service_url=args.service_url)
    success = test.run_all_tests()

    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
