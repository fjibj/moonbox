---
layout: global
title: Deploying
---

* This will become a table of contents (this text will be scraped).
{:toc}
#### 环境准备

- JDK 1.8
- Redis 3.x.x
- 配置执行启动脚本的机器到其他所有机器SSH免密码登录

#### 部署配置

下载 moonbox-0.2.0-dist.tar.gz 包，或者使用如下命令自行编译

```
git clone -b 0.2.0 https://github.com/edp963/moonbox.git
cd moonbox
sh dev/build.sh
```

解压moonbox-0.2.0-dist.tar.gz

```
tar -zxvf moonbox-0.2.0-dist.tar.gz
```

moonbox的配置分为环境变量、集群拓扑、运行参数三个部分，下面分别解释每个部分各表示什么含义以及如何配置。

- 环境变量

  配置文件为conf/moonbox-env.sh

  ```
  export MOONBOX_SSH_OPTIONS="-p 22" # 用于配置ssh端口，如默认无需修改
  export MOONBOX_HOME=/path/to/installed/dir # 配置moonbox安装目录
  ```

- 集群拓扑

  用于描述集群拓扑，配置文件为$MOONBOX_HOME/conf/nodes。moonbox以master-slave集群模式运行，支持配置多个master用于主备。示例如下，请按照实际情况配置。

  ```
  moonbox.gird.master.1   grid://host1:2551
  moonbox.gird.master.2   grid://host2:2551
  moonbox.gird.worker.1   host3
  moonbox.gird.worker.2   host4
  ```

- 运行参数

  用于配置运行时参数，配置文件为$MOONBOX_HOME/conf/moonbox-defaults.conf。以下为moonbox最简配置，请根据实际情况修改，更多配置请参考Configuration章节。

  ```
  moonbox {
      rest.server {
          port = 8080
      }
      tcp.server {
          port = 10010
      }
      catalog {
      	  implementation = "h2"
      	  url = "jdbc:h2:mem:testdb0;DB_CLOSE_DELAY=-1"
      	  user = "testUser"
      	  password = "testPass"
      	  driver = "org.h2.Driver"
      }
      cache {
      	  implementation = "redis"
      	  servers = "host:port"
      }
      mixcal {
          implementation = "spark"
      	  spark.master = "local[*]"
      	  spark.loglevel = "INFO"
      	  spark.app.name = "test1"
      	  pushdown.enable = true
      	  column.permission.enable = false
      }
  }
  ```

将moonbox文件夹分发到conf/nodes中所配置的所有机器上,其所处目录应当与当前机器$MOONBOX_HOME一致。

#### 启动与停止

我们为用户提供了一键启动与停止集群的脚本,位于$MOONBOX_HOME/sbin目录下。
  - 启动集群
  在任意节点执行
  ```
  cd $MOONBOX_HOME
  sbin/start-all.sh
  ```
  - 停止集群
  在任意节点执行
  ```
  cd $MOONBOX_HOME
  sbin/stop-all.sh
  ```
备注: 执行启动和停止脚本的机器需要配置到其他机器的ssh免密码登录。

  ​