/**
 * Copyright 2014 Confluent Inc.
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
package io.confluent.kafka.schemaregistry.zookeeper;

import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryTimeoutException;
import io.confluent.kafka.schemaregistry.storage.KafkaSchemaRegistry;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryStoreException;
import kafka.utils.ZkUtils;

public class ZookeeperMasterElector {

  private static final Logger log = LoggerFactory.getLogger(ZookeeperMasterElector.class);
  private static final String MASTER_PATH = "/schema_registry_master";

  private final ZkClient zkClient;
  private final SchemaRegistryIdentity myIdentity;
  private final String myIdentityString;
  private final KafkaSchemaRegistry schemaRegistry;


  public ZookeeperMasterElector(ZkClient zkClient, 
                                SchemaRegistryIdentity myIdentity,
                                KafkaSchemaRegistry schemaRegistry, 
                                boolean isEligibleForMasterElection)
      throws SchemaRegistryTimeoutException, SchemaRegistryStoreException {
    this.zkClient = zkClient;
    this.myIdentity = myIdentity;
    try {
      this.myIdentityString = myIdentity.toJson();
    } catch (IOException e) {
      throw new SchemaRegistryStoreException(String.format(
          "Error while serializing schema registry identity %s to json", myIdentity.toString()), e);
    }
    this.schemaRegistry = schemaRegistry;

    zkClient.subscribeStateChanges(new SessionExpirationListener(isEligibleForMasterElection));
    zkClient.subscribeDataChanges(MASTER_PATH, 
                                  new MasterChangeListener(isEligibleForMasterElection));
    if (isEligibleForMasterElection) {
      electMaster();  
    } else {
      readCurrentMaster();
    }    
  }

  public void close() {
    zkClient.unsubscribeAll();
  }

  public void electMaster() throws
      SchemaRegistryStoreException, SchemaRegistryTimeoutException {
    SchemaRegistryIdentity masterIdentity = null;
    try {
      ZkUtils.createEphemeralPathExpectConflict(zkClient, MASTER_PATH, myIdentityString);
      log.info("Successfully elected the new master: " + myIdentityString);
      masterIdentity = myIdentity;
      schemaRegistry.setMaster(masterIdentity);
    } catch (ZkNodeExistsException znee) {
      readCurrentMaster();
    }
  }

  public void readCurrentMaster()
      throws SchemaRegistryTimeoutException, SchemaRegistryStoreException {
    SchemaRegistryIdentity masterIdentity = null;
    // If someone else has written the path, read the new master back
    try {
      String masterIdentityString = ZkUtils.readData(zkClient, MASTER_PATH)._1();
      try {
        masterIdentity = SchemaRegistryIdentity.fromJson(masterIdentityString);
      } catch (IOException ioe) {
        log.error("Can't parse schema registry identity json string " + masterIdentityString);
      }
    } catch (ZkNoNodeException znne) {
      // NOTE: masterIdentity is already initialized to null. The master will then be updated to 
      // null so register requests directed to this node can throw the right error code back
    }
    schemaRegistry.setMaster(masterIdentity);
  }
  
  private class MasterChangeListener implements IZkDataListener {
    private final boolean isEligibleForMasterElection;
    
    public MasterChangeListener(boolean isEligibleForMasterElection) {
      this.isEligibleForMasterElection = isEligibleForMasterElection;  
    }
    
    /**
     * Called when the leader information stored in zookeeper has changed. Record the new leader in
     * memory
     *
     * @throws Exception On any error.
     */
    @Override
    public void handleDataChange(String dataPath, Object data) {
      try {
        if (!isEligibleForMasterElection) {
          readCurrentMaster();
        } else {
          electMaster();
        }
      } catch (SchemaRegistryException e) {
        log.error("Error while reading the schema registry master", e);
      }
    }

    /**
     * Called when the leader information stored in zookeeper has been delete. Try to elect as the
     * leader
     *
     * @throws Exception On any error.
     */
    @Override
    public void handleDataDeleted(String dataPath) throws Exception {
      if (isEligibleForMasterElection) {
        electMaster();  
      } else {
        schemaRegistry.setMaster(null);
      }      
    }
  }

  private class SessionExpirationListener implements IZkStateListener {

    private final boolean isEligibleForMasterElection;

    public SessionExpirationListener(boolean isEligibleForMasterElection) {
      this.isEligibleForMasterElection = isEligibleForMasterElection;
    }

    @Override
    public void handleStateChanged(Watcher.Event.KeeperState state) {
      // do nothing, since zkclient will do reconnect for us.
    }

    /**
     * Called after the zookeeper session has expired and a new session has been created. You would
     * have to re-create any ephemeral nodes here.
     *
     * @throws Exception On any error.
     */
    @Override
    public void handleNewSession() throws Exception {
      if (isEligibleForMasterElection) {
        electMaster();  
      } else {
        readCurrentMaster();
      }
    }
  }
}
