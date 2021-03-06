package com.ibm.streamsx.topology.context.remote;

import com.ibm.streamsx.topology.internal.context.streamsrest.BuildServiceContext;
import com.ibm.streamsx.topology.internal.context.streamsrest.DistributedStreamsRestContext;
import com.ibm.streamsx.topology.internal.context.remote.RemoteBuildAndSubmitRemoteContext;
import com.ibm.streamsx.topology.internal.context.remote.Sas4BuildContext;
import com.ibm.streamsx.topology.internal.context.remote.ToolkitRemoteContext;
import com.ibm.streamsx.topology.internal.context.remote.ZippedToolkitRemoteContext;
import com.ibm.streamsx.topology.internal.messages.Messages;

public class RemoteContextFactory {
    
    public static RemoteContext<?> getRemoteContext(String type) {
        return getRemoteContext(RemoteContext.Type.valueOf(type), true);
    }

    public static RemoteContext<?> getRemoteContext(final RemoteContext.Type type, final boolean keepArtifact) {
        switch (type) {
        case TOOLKIT:
            return new ToolkitRemoteContext(keepArtifact);
        case BUILD_ARCHIVE:
            return new ZippedToolkitRemoteContext(keepArtifact);
        case ANALYTICS_SERVICE:
        case STREAMING_ANALYTICS_SERVICE:
        	return new RemoteBuildAndSubmitRemoteContext();
        case BUNDLE:
            return new BuildServiceContext();
        case DISTRIBUTED:
            return new DistributedStreamsRestContext();
        case SAS_BUNDLE:
            return new Sas4BuildContext();
        default:
            throw new IllegalArgumentException(Messages.getString("CONTEXT_UNKNOWN_TYPE", type));
        }
    }
}
