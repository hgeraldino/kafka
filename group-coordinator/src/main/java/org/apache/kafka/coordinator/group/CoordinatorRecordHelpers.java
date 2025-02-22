/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.coordinator.group;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.requests.OffsetCommitRequest;
import org.apache.kafka.coordinator.group.Group.GroupType;
import org.apache.kafka.coordinator.group.classic.ClassicGroup;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupCurrentMemberAssignmentKey;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupCurrentMemberAssignmentValue;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupMemberMetadataKey;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupMemberMetadataValue;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupMetadataKey;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupMetadataValue;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupPartitionMetadataKey;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupPartitionMetadataValue;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupTargetAssignmentMemberKey;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupTargetAssignmentMemberValue;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupTargetAssignmentMetadataKey;
import org.apache.kafka.coordinator.group.generated.ConsumerGroupTargetAssignmentMetadataValue;
import org.apache.kafka.coordinator.group.generated.GroupMetadataKey;
import org.apache.kafka.coordinator.group.generated.GroupMetadataValue;
import org.apache.kafka.coordinator.group.generated.OffsetCommitKey;
import org.apache.kafka.coordinator.group.generated.OffsetCommitValue;
import org.apache.kafka.coordinator.group.generated.ShareGroupMetadataKey;
import org.apache.kafka.coordinator.group.modern.TopicMetadata;
import org.apache.kafka.coordinator.group.modern.consumer.ConsumerGroupMember;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.server.common.MetadataVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class contains helper methods to create records stored in
 * the __consumer_offsets topic.
 */
public class CoordinatorRecordHelpers {
    private CoordinatorRecordHelpers() {}

    /**
     * Creates a ConsumerGroupMemberMetadata record.
     *
     * @param groupId   The consumer group id.
     * @param member    The consumer group member.
     * @return The record.
     */
    public static CoordinatorRecord newMemberSubscriptionRecord(
        String groupId,
        ConsumerGroupMember member
    ) {
        List<String> topicNames = new ArrayList<>(member.subscribedTopicNames());
        Collections.sort(topicNames);
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupMemberMetadataKey()
                    .setGroupId(groupId)
                    .setMemberId(member.memberId()),
                (short) 5
            ),
            new ApiMessageAndVersion(
                new ConsumerGroupMemberMetadataValue()
                    .setRackId(member.rackId())
                    .setInstanceId(member.instanceId())
                    .setClientId(member.clientId())
                    .setClientHost(member.clientHost())
                    .setSubscribedTopicNames(topicNames)
                    .setSubscribedTopicRegex(member.subscribedTopicRegex())
                    .setServerAssignor(member.serverAssignorName().orElse(null))
                    .setRebalanceTimeoutMs(member.rebalanceTimeoutMs())
                    .setClassicMemberMetadata(member.classicMemberMetadata().orElse(null)),
                (short) 0
            )
        );
    }

    /**
     * Creates a ConsumerGroupMemberMetadata tombstone.
     *
     * @param groupId   The consumer group id.
     * @param memberId  The consumer group member id.
     * @return The record.
     */
    public static CoordinatorRecord newMemberSubscriptionTombstoneRecord(
        String groupId,
        String memberId
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupMemberMetadataKey()
                    .setGroupId(groupId)
                    .setMemberId(memberId),
                (short) 5
            ),
            null // Tombstone.
        );
    }

    /**
     * Creates a ConsumerGroupPartitionMetadata record.
     *
     * @param groupId                   The consumer group id.
     * @param newSubscriptionMetadata   The subscription metadata.
     * @return The record.
     */
    public static CoordinatorRecord newGroupSubscriptionMetadataRecord(
        String groupId,
        Map<String, TopicMetadata> newSubscriptionMetadata
    ) {
        ConsumerGroupPartitionMetadataValue value = new ConsumerGroupPartitionMetadataValue();
        newSubscriptionMetadata.forEach((topicName, topicMetadata) -> {
            List<ConsumerGroupPartitionMetadataValue.PartitionMetadata> partitionMetadata = new ArrayList<>();
            // If the partition rack information map is empty, store an empty list in the record.
            if (!topicMetadata.partitionRacks().isEmpty()) {
                topicMetadata.partitionRacks().forEach((partition, racks) ->
                    partitionMetadata.add(new ConsumerGroupPartitionMetadataValue.PartitionMetadata()
                        .setPartition(partition)
                        .setRacks(new ArrayList<>(racks))
                    )
                );
            }
            value.topics().add(new ConsumerGroupPartitionMetadataValue.TopicMetadata()
                .setTopicId(topicMetadata.id())
                .setTopicName(topicMetadata.name())
                .setNumPartitions(topicMetadata.numPartitions())
                .setPartitionMetadata(partitionMetadata)
            );
        });

        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupPartitionMetadataKey()
                    .setGroupId(groupId),
                (short) 4
            ),
            new ApiMessageAndVersion(
                value,
                (short) 0
            )
        );
    }

    /**
     * Creates a ConsumerGroupPartitionMetadata tombstone.
     *
     * @param groupId   The consumer group id.
     * @return The record.
     */
    public static CoordinatorRecord newGroupSubscriptionMetadataTombstoneRecord(
        String groupId
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupPartitionMetadataKey()
                    .setGroupId(groupId),
                (short) 4
            ),
            null // Tombstone.
        );
    }

    /**
     * Creates a ConsumerGroupMetadata record.
     *
     * @param groupId       The consumer group id.
     * @param newGroupEpoch The consumer group epoch.
     * @return The record.
     */
    public static CoordinatorRecord newGroupEpochRecord(
        String groupId,
        int newGroupEpoch
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupMetadataKey()
                    .setGroupId(groupId),
                (short) 3
            ),
            new ApiMessageAndVersion(
                new ConsumerGroupMetadataValue()
                    .setEpoch(newGroupEpoch),
                (short) 0
            )
        );
    }

    /**
     * Creates a ConsumerGroupMetadata tombstone.
     *
     * @param groupId   The consumer group id.
     * @return The record.
     */
    public static CoordinatorRecord newGroupEpochTombstoneRecord(
        String groupId
    ) {
        return newGroupEpochTombstoneRecord(groupId, GroupType.CONSUMER);
    }

    /**
     * Creates a ConsumerGroupMetadata tombstone.
     *
     * @param groupId   The consumer group id.
     * @param groupType The group type.
     * @return The record.
     */
    public static CoordinatorRecord newGroupEpochTombstoneRecord(
        String groupId,
        GroupType groupType
    ) {
        if (groupType == GroupType.CONSUMER) {
            return new CoordinatorRecord(
                new ApiMessageAndVersion(
                    new ConsumerGroupMetadataKey()
                        .setGroupId(groupId),
                    (short) 3
                ),
                null // Tombstone.
            );
        } else if (groupType == GroupType.SHARE) {
            return new CoordinatorRecord(
                new ApiMessageAndVersion(
                    new ShareGroupMetadataKey()
                        .setGroupId(groupId),
                    (short) 11
                ),
                null // Tombstone.
            );
        }
        throw new IllegalArgumentException("Unsupported group type: " + groupType);
    }

    /**
     * Creates a ConsumerGroupTargetAssignmentMember record.
     *
     * @param groupId       The consumer group id.
     * @param memberId      The consumer group member id.
     * @param partitions    The target partitions of the member.
     * @return The record.
     */
    public static CoordinatorRecord newTargetAssignmentRecord(
        String groupId,
        String memberId,
        Map<Uuid, Set<Integer>> partitions
    ) {
        List<ConsumerGroupTargetAssignmentMemberValue.TopicPartition> topicPartitions =
            new ArrayList<>(partitions.size());

        for (Map.Entry<Uuid, Set<Integer>> entry : partitions.entrySet()) {
            topicPartitions.add(
                new ConsumerGroupTargetAssignmentMemberValue.TopicPartition()
                    .setTopicId(entry.getKey())
                    .setPartitions(new ArrayList<>(entry.getValue()))
            );
        }

        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupTargetAssignmentMemberKey()
                    .setGroupId(groupId)
                    .setMemberId(memberId),
                (short) 7
            ),
            new ApiMessageAndVersion(
                new ConsumerGroupTargetAssignmentMemberValue()
                    .setTopicPartitions(topicPartitions),
                (short) 0
            )
        );
    }

    /**
     * Creates a ConsumerGroupTargetAssignmentMember tombstone.
     *
     * @param groupId       The consumer group id.
     * @param memberId      The consumer group member id.
     * @return The record.
     */
    public static CoordinatorRecord newTargetAssignmentTombstoneRecord(
        String groupId,
        String memberId
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupTargetAssignmentMemberKey()
                    .setGroupId(groupId)
                    .setMemberId(memberId),
                (short) 7
            ),
            null // Tombstone.
        );
    }

    /**
     * Creates a ConsumerGroupTargetAssignmentMetadata record.
     *
     * @param groupId           The consumer group id.
     * @param assignmentEpoch   The consumer group epoch.
     * @return The record.
     */
    public static CoordinatorRecord newTargetAssignmentEpochRecord(
        String groupId,
        int assignmentEpoch
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupTargetAssignmentMetadataKey()
                    .setGroupId(groupId),
                (short) 6
            ),
            new ApiMessageAndVersion(
                new ConsumerGroupTargetAssignmentMetadataValue()
                    .setAssignmentEpoch(assignmentEpoch),
                (short) 0
            )
        );
    }

    /**
     * Creates a ConsumerGroupTargetAssignmentMetadata tombstone.
     *
     * @param groupId   The consumer group id.
     * @return The record.
     */
    public static CoordinatorRecord newTargetAssignmentEpochTombstoneRecord(
        String groupId
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupTargetAssignmentMetadataKey()
                    .setGroupId(groupId),
                (short) 6
            ),
            null // Tombstone.
        );
    }

    /**
     * Creates a ConsumerGroupCurrentMemberAssignment record.
     *
     * @param groupId   The consumer group id.
     * @param member    The consumer group member.
     * @return The record.
     */
    public static CoordinatorRecord newCurrentAssignmentRecord(
        String groupId,
        ConsumerGroupMember member
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupCurrentMemberAssignmentKey()
                    .setGroupId(groupId)
                    .setMemberId(member.memberId()),
                (short) 8
            ),
            new ApiMessageAndVersion(
                new ConsumerGroupCurrentMemberAssignmentValue()
                    .setMemberEpoch(member.memberEpoch())
                    .setPreviousMemberEpoch(member.previousMemberEpoch())
                    .setState(member.state().value())
                    .setAssignedPartitions(toTopicPartitions(member.assignedPartitions()))
                    .setPartitionsPendingRevocation(toTopicPartitions(member.partitionsPendingRevocation())),
                (short) 0
            )
        );
    }

    /**
     * Creates a ConsumerGroupCurrentMemberAssignment tombstone.
     *
     * @param groupId   The consumer group id.
     * @param memberId  The consumer group member id.
     * @return The record.
     */
    public static CoordinatorRecord newCurrentAssignmentTombstoneRecord(
        String groupId,
        String memberId
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new ConsumerGroupCurrentMemberAssignmentKey()
                    .setGroupId(groupId)
                    .setMemberId(memberId),
                (short) 8
            ),
            null // Tombstone
        );
    }

    /**
     * Creates a GroupMetadata record.
     *
     * @param group              The classic group.
     * @param assignment         The classic group assignment.
     * @param metadataVersion    The metadata version.
     * @return The record.
     */
    public static CoordinatorRecord newGroupMetadataRecord(
        ClassicGroup group,
        Map<String, byte[]> assignment,
        MetadataVersion metadataVersion
    ) {
        List<GroupMetadataValue.MemberMetadata> members = new ArrayList<>(group.allMembers().size());
        group.allMembers().forEach(member -> {
            byte[] subscription = group.protocolName().map(member::metadata).orElse(null);
            if (subscription == null) {
                throw new IllegalStateException("Attempted to write non-empty group metadata with no defined protocol.");
            }

            byte[] memberAssignment = assignment.get(member.memberId());
            if (memberAssignment == null) {
                throw new IllegalStateException("Attempted to write member " + member.memberId() +
                    " of group " + group.groupId() + " with no assignment.");
            }

            members.add(
                new GroupMetadataValue.MemberMetadata()
                    .setMemberId(member.memberId())
                    .setClientId(member.clientId())
                    .setClientHost(member.clientHost())
                    .setRebalanceTimeout(member.rebalanceTimeoutMs())
                    .setSessionTimeout(member.sessionTimeoutMs())
                    .setGroupInstanceId(member.groupInstanceId().orElse(null))
                    .setSubscription(subscription)
                    .setAssignment(memberAssignment)
            );
        });

        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new GroupMetadataKey()
                    .setGroup(group.groupId()),
                (short) 2
            ),
            new ApiMessageAndVersion(
                new GroupMetadataValue()
                    .setProtocol(group.protocolName().orElse(null))
                    .setProtocolType(group.protocolType().orElse(""))
                    .setGeneration(group.generationId())
                    .setLeader(group.leaderOrNull())
                    .setCurrentStateTimestamp(group.currentStateTimestampOrDefault())
                    .setMembers(members),
                metadataVersion.groupMetadataValueVersion()
            )
        );
    }

    /**
     * Creates a GroupMetadata tombstone.
     *
     * @param groupId  The group id.
     * @return The record.
     */
    public static CoordinatorRecord newGroupMetadataTombstoneRecord(
        String groupId
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new GroupMetadataKey()
                    .setGroup(groupId),
                (short) 2
            ),
            null // Tombstone
        );
    }

    /**
     * Creates an empty GroupMetadata record.
     *
     * @param group              The classic group.
     * @param metadataVersion    The metadata version.
     * @return The record.
     */
    public static CoordinatorRecord newEmptyGroupMetadataRecord(
        ClassicGroup group,
        MetadataVersion metadataVersion
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new GroupMetadataKey()
                    .setGroup(group.groupId()),
                (short) 2
            ),
            new ApiMessageAndVersion(
                new GroupMetadataValue()
                    .setProtocol(null)
                    .setProtocolType("")
                    .setGeneration(0)
                    .setLeader(null)
                    .setCurrentStateTimestamp(group.currentStateTimestampOrDefault())
                    .setMembers(Collections.emptyList()),
                metadataVersion.groupMetadataValueVersion()
            )
        );
    }

    /**
     * Creates an OffsetCommit record.
     *
     * @param groupId           The group id.
     * @param topic             The topic name.
     * @param partitionId       The partition id.
     * @param offsetAndMetadata The offset and metadata.
     * @param metadataVersion   The metadata version.
     * @return The record.
     */
    public static CoordinatorRecord newOffsetCommitRecord(
        String groupId,
        String topic,
        int partitionId,
        OffsetAndMetadata offsetAndMetadata,
        MetadataVersion metadataVersion
    ) {
        short version = metadataVersion.offsetCommitValueVersion(offsetAndMetadata.expireTimestampMs.isPresent());

        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new OffsetCommitKey()
                    .setGroup(groupId)
                    .setTopic(topic)
                    .setPartition(partitionId),
                (short) 1
            ),
            new ApiMessageAndVersion(
                new OffsetCommitValue()
                    .setOffset(offsetAndMetadata.committedOffset)
                    .setLeaderEpoch(offsetAndMetadata.leaderEpoch.orElse(RecordBatch.NO_PARTITION_LEADER_EPOCH))
                    .setMetadata(offsetAndMetadata.metadata)
                    .setCommitTimestamp(offsetAndMetadata.commitTimestampMs)
                    // Version 1 has a non-empty expireTimestamp field
                    .setExpireTimestamp(offsetAndMetadata.expireTimestampMs.orElse(OffsetCommitRequest.DEFAULT_TIMESTAMP)),
                version
            )
        );
    }

    /**
     * Creates an OffsetCommit tombstone record.
     *
     * @param groupId           The group id.
     * @param topic             The topic name.
     * @param partitionId       The partition id.
     * @return The record.
     */
    public static CoordinatorRecord newOffsetCommitTombstoneRecord(
        String groupId,
        String topic,
        int partitionId
    ) {
        return new CoordinatorRecord(
            new ApiMessageAndVersion(
                new OffsetCommitKey()
                    .setGroup(groupId)
                    .setTopic(topic)
                    .setPartition(partitionId),
                (short) 1
            ),
            null
        );
    }

    private static List<ConsumerGroupCurrentMemberAssignmentValue.TopicPartitions> toTopicPartitions(
        Map<Uuid, Set<Integer>> topicPartitions
    ) {
        List<ConsumerGroupCurrentMemberAssignmentValue.TopicPartitions> topics = new ArrayList<>(topicPartitions.size());
        topicPartitions.forEach((topicId, partitions) ->
            topics.add(new ConsumerGroupCurrentMemberAssignmentValue.TopicPartitions()
                .setTopicId(topicId)
                .setPartitions(new ArrayList<>(partitions)))
        );
        return topics;
    }
}
