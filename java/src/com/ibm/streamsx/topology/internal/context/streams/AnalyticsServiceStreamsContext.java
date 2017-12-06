/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2015  
 */
package com.ibm.streamsx.topology.internal.context.streams;

import static com.ibm.streamsx.topology.context.ContextProperties.FORCE_REMOTE_BUILD;
import static com.ibm.streamsx.topology.internal.context.remote.DeployKeys.deploy;
import static com.ibm.streamsx.topology.internal.context.remote.DeployKeys.keepArtifacts;
import static com.ibm.streamsx.topology.internal.context.remote.RemoteBuildAndSubmitRemoteContext.streamingAnalyticServiceFromDeploy;
import static com.ibm.streamsx.topology.internal.gson.GsonUtilities.jboolean;
import static com.ibm.streamsx.topology.internal.streaminganalytics.VcapServices.getVCAPService;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.Future;

import com.google.gson.JsonObject;
import com.ibm.streamsx.rest.Job;
import com.ibm.streamsx.rest.Result;
import com.ibm.streamsx.rest.StreamingAnalyticsService;
import com.ibm.streamsx.topology.context.remote.RemoteContext;
import com.ibm.streamsx.topology.internal.context.remote.DeployKeys;
import com.ibm.streamsx.topology.internal.context.remote.RemoteContexts;
import com.ibm.streamsx.topology.internal.context.remote.SubmissionResultsKeys;
import com.ibm.streamsx.topology.internal.context.service.RemoteStreamingAnalyticsServiceStreamsContext;
import com.ibm.streamsx.topology.internal.gson.GsonUtilities;
import com.ibm.streamsx.topology.internal.process.CompletedFuture;

public class AnalyticsServiceStreamsContext extends
        BundleUserStreamsContext<BigInteger> {

    private final Type type;
    
    public AnalyticsServiceStreamsContext(Type type) {
        super(false);
        this.type = type;
    }

    @Override
    public Type getType() {
        return type;
    }
    
    @Override
    protected Future<BigInteger> action(AppEntity entity) throws Exception {
        if (useRemoteBuild(entity)) {
            RemoteStreamingAnalyticsServiceStreamsContext rc = new RemoteStreamingAnalyticsServiceStreamsContext();
            return rc.submit(entity.submission);
        }

        return super.action(entity);
    }
    
    /**
     * See if the remote build service should be used.
     * If STREAMS_INSTALL was not set is handled elsewhere,
     * so this path assumes that STREAMS_INSTALL is set and not empty.
     * 
     * Remote build if:
     *  - FORCE_REMOTE_BUILD is set to true.
     */
    private boolean useRemoteBuild(AppEntity entity) {
        if (jboolean(deploy(entity.submission), FORCE_REMOTE_BUILD))
            return true;
        
        return false;
    }
    
    
    @Override
    Future<BigInteger> invoke(AppEntity entity, File bundle) throws Exception {
        try {           
            BigInteger jobId = submitJobToService(bundle, entity.submission);
         
            return new CompletedFuture<BigInteger>(jobId);
        } finally {
            if (!keepArtifacts(entity.submission))
                bundle.delete();
        }
    }
    
    /**
     * Verify we have a valid Streaming Analytic service
     * information before we attempt anything.
     */
    @Override
    protected void preSubmit(AppEntity entity) {
        
            
        try {
            if (entity.submission != null)
                getVCAPService(deploy(entity.submission));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private BigInteger submitJobToService(File bundle, JsonObject submission) throws IOException {
        JsonObject deploy =  deploy(submission);
        JsonObject jco = DeployKeys.copyJobConfigOverlays(deploy);

        final StreamingAnalyticsService sas = streamingAnalyticServiceFromDeploy(deploy); 
        
        RemoteContexts.checkServiceRunning(sas);

        Result<Job, JsonObject> submitResult = sas.submitJob(bundle, jco);
        final JsonObject submissionResult = GsonUtilities.objectCreate(submission,
                RemoteContext.SUBMISSION_RESULTS);
        GsonUtilities.addAll(submissionResult, submitResult.getRawResult());
        
        // Ensure job id is in a known place regardless of version
        final String jobId = submitResult.getId();
        GsonUtilities.addToObject(submissionResult, SubmissionResultsKeys.JOB_ID, jobId);

        return new BigInteger(jobId);
    }
}
