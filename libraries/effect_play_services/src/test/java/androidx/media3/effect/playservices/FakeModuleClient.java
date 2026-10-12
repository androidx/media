/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect.playservices;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fake {@link ModuleClient} implementation for tests. */
/* package */ class FakeModuleClient implements ModuleClient {

  /* package */ final AtomicBoolean installModuleCalled;
  /* package */ Task<Boolean> deviceSupportedTask;
  /* package */ Task<Boolean> moduleInstalledTask;
  /* package */ Task<Boolean> installModuleTask;

  /* package */ FakeModuleClient() {
    this.installModuleCalled = new AtomicBoolean(false);
    this.deviceSupportedTask = Tasks.forResult(true);
    this.moduleInstalledTask = Tasks.forResult(true);
    this.installModuleTask = Tasks.forResult(true);
  }

  @Override
  public Task<Boolean> isDeviceSupported() {
    return deviceSupportedTask;
  }

  @Override
  public Task<Boolean> isModuleInstalled() {
    return moduleInstalledTask;
  }

  @Override
  public Task<Boolean> installModule() {
    installModuleCalled.set(true);
    return installModuleTask;
  }
}
