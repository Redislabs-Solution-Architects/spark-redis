The supported configuration parameters are:

## Spark Context configuration parameters

* `spark.redis.host` - host or IP of the initial node we connect to. The connector will read the cluster
topology from the initial node, so there is no need to provide the rest of the cluster nodes.
* `spark.redis.port` - the initial node's TCP redis port.
* `spark.redis.user` - the initial node's AUTH user
* `spark.redis.auth` - the initial node's AUTH password
* `spark.redis.db` - optional DB number. Avoid using this, especially in cluster mode.
* `spark.redis.timeout` - connection timeout in ms, 2000 ms by default
* `spark.redis.max.pipeline.size` - the maximum number of commands per pipeline (used to batch commands). The default value is 100.
* `spark.redis.scan.count` - count option of SCAN command (used to iterate over keys). The default value is 100.
* `spark.redis.rdd.write.iterator.grouping.size` - applied for RDD write operations, the number of items to be grouped when iterating over underlying RDD partition 
* `spark.redis.ssl` - set to true to use tls
* `spark.redis.client.name` - optional client name. Every connection the connector opens, on the driver and on the
executors and to every node of a cluster, sets it with `CLIENT SETNAME`, so `CLIENT LIST` shows it as `name=...`.
Redis accepts printable ASCII only, with no spaces; any other value fails on the driver with an
`IllegalArgumentException`, before a connection is opened. Unset or empty (the default) leaves connections unnamed.
A DataFrame source can override it with the `client.name` option.

## DataFrame source options

Options on `spark.read` and `df.write` with `.format("org.apache.spark.sql.redis")` override the settings above for
that source only. The full list is in [DataFrame options](dataframe.md#dataframe-options).

* `client.name` - overrides `spark.redis.client.name` for this source's connections. An empty value leaves them
unnamed.

```scala
df.write
  .format("org.apache.spark.sql.redis")
  .option("table", "person")
  .option("client.name", "person-writer")
  .save()
```



