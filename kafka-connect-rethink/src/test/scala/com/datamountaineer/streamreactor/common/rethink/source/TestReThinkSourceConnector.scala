/*
 * Copyright 2017 Datamountaineer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datamountaineer.streamreactor.common.rethink.source

import java.util

import com.datamountaineer.streamreactor.common.rethink.TestBase

/**
  * Created by andrew@datamountaineer.com on 22/06/16. 
  * stream-reactor-maven
  */
class TestReThinkSourceConnector extends TestBase {
  "Should start a ReThink Connector" in {
    val props = getPropsSource2
    val connector = new ReThinkSourceConnector()
    connector.start(props)
    connector.taskClass() shouldBe classOf[ReThinkSourceTask]
    val taskConfigs: util.List[util.Map[String, String]] = connector.taskConfigs(2)
    taskConfigs.size() shouldBe 2
    connector.stop()
  }
}