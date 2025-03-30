/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.server;

import org.elasticsearch.server.internal.InternalServer;
import org.elasticsearch.util.settings.ImmutableSettings;
import org.elasticsearch.util.settings.Settings;

/**
 * A server builder is used to construct a {@link Server} instance.
 *
 * <p>StrategySettings will be loaded relative to the ES home (with or without <tt>config/</tt> prefix) and if not found,
 * within the classpath (with or without <tt>config/<tt> prefix). The strategySettings file loaded can either be named
 * <tt>elasticsearch.yml</tt> or <tt>elasticsearch.json</tt>). Loading strategySettings can be disabled by calling
 * {@link #loadConfigSettings(boolean)} with <tt>false<tt>.
 *
 * <p>Explicit strategySettings can be passed by using the {@link #settings(Settings)} method.
 *
 * <p>In any case, strategySettings will be resolved from system properties as well that are either prefixed with <tt>es.</tt>
 * or <tt>elasticsearch.</tt>.
 *
 * <p>An example for creating a simple server with optional strategySettings loaded from the classpath:
 *
 * <pre>
 * Server server = ServerBuilder.serverBuilder().server();
 * </pre>
 *
 * <p>An example for creating a server with explicit strategySettings (in this case, a node in the cluster that does not hold
 * data):
 *
 * <pre>
 * Server server = ServerBuilder.serverBuilder()
 *                      .strategySettings(ImmutableSettings.settingsBuilder().putBoolean("node.data", false)
 *                      .server();
 * </pre>
 *
 * <p>When done with the server, make sure you call {@link Server#close()} on it.
 *
 * @author kimchy (Shay Banon)
 */
public class ServerBuilder {

    private Settings settings = ImmutableSettings.Builder.EMPTY_SETTINGS;

    private boolean loadConfigSettings = true;

    /**
     * A convenient factory method to create a {@link ServerBuilder}.
     */
    public static ServerBuilder serverBuilder() {
        return new ServerBuilder();
    }

    /**
     * Explicit server strategySettings to set.
     */
    public ServerBuilder settings(Settings.Builder settings) {
        return settings(settings.build());
    }

    /**
     * Explicit server strategySettings to set.
     */
    public ServerBuilder settings(Settings settings) {
        this.settings = settings;
        return this;
    }

    /**
     * Should the server builder automatically try and load config strategySettings from the file system / classpath. Defaults
     * to <tt>true</tt>.
     */
    public ServerBuilder loadConfigSettings(boolean loadConfigSettings) {
        this.loadConfigSettings = loadConfigSettings;
        return this;
    }

    /**
     * Builds the server without starting it.
     */
    public Server build() {
        return new InternalServer(settings, loadConfigSettings);
    }

    /**
     * {@link #build()}s and starts the server.
     */
    public Server server() {
        return build().start();
    }
}
