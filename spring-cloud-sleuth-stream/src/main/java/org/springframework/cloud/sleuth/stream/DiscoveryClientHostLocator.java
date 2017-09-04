/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.stream;

import java.net.InetAddress;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.sleuth.Span;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * An {@link HostLocator} that tries to find local service information from a
 * {@link DiscoveryClient}.
 *
 * You can override the value of service id by {@link ZipkinProperties#setName(String)}
 *
 * @author Dave Syer
 * @since 1.0.0
 */
public class DiscoveryClientHostLocator implements HostLocator {

	private final DiscoveryClient client;
	private final ZipkinProperties zipkinProperties;

	public DiscoveryClientHostLocator(DiscoveryClient client, ZipkinProperties zipkinProperties) {
		this.client = client;
		Assert.notNull(this.client, "client");
		this.zipkinProperties = zipkinProperties;
	}

	@Override
	public Host locate(Span span) {
		ServiceInstance instance = this.client.getLocalServiceInstance();
		String serviceId = StringUtils.hasText(this.zipkinProperties.getService().getName()) ?
				this.zipkinProperties.getService().getName() : instance.getServiceId();
		return new Host(serviceId, getIpAddress(instance),
				instance.getPort());
	}

	private String getIpAddress(ServiceInstance instance) {
		try {
			InetAddress address = InetAddress.getByName(instance.getHost());
			return address.getHostAddress();
		}
		catch (Exception e) {
			return "0.0.0.0";
		}
	}

}
