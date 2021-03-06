/*
 * Copyright 2015-2017 Canoo Engineering AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.canoo.platform.server.spi;

import com.canoo.dp.impl.platform.core.Assert;
import com.canoo.platform.core.PlatformConfiguration;
import org.apiguardian.api.API;

import java.util.Collections;
import java.util.List;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

/**
 * Abstract implementation of the {@link ServerModule} interface that can be used as a base to create new server module implementations.
 *
 * @author Hendrik Ebbers
 */
@API(since = "0.x", status = EXPERIMENTAL)
public abstract class AbstractBaseModule implements ServerModule {

    @Override
    public List<String> getModuleDependencies() {
        return Collections.emptyList();
    }

    @Override
    public boolean shouldBoot(PlatformConfiguration configuration) {
        return Assert.requireNonNull(configuration, "configuration").getBooleanProperty(getActivePropertyName(), true);
    }

    /**
     * Returns the name of the property that will be used to check if the module is active or not. By default the module will be active. The property can be defined in the Dolphin Platform configuration (see {@link PlatformConfiguration}) as <code>false</code> to deactive the module
     * @return the name of the property
     */
    protected abstract String getActivePropertyName();
}
