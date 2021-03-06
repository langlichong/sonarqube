
/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import ce.BombConfig;
import ce.ComponentBombReportAnalysisComponentProvider;
import ce.IseTaskProcessor;
import ce.OkTaskProcessor;
import ce.OomTaskProcessor;
import ce.ws.BombActivatorAction;
import ce.ws.FakeGovWs;
import ce.ws.SubmitAction;
import org.sonar.api.SonarQubeSide;
import org.sonar.core.extension.CoreExtension;
import systemPasscode.SystemPasscodeWebService;
import workerCount.FakeWorkerCountProviderImpl;
import workerCount.RefreshWorkerCountAction;
import workerlatch.LatchControllerWorkerMeasureComputer;
import workerlatch.WorkerLatchMetrics;

import static org.sonar.api.SonarQubeSide.COMPUTE_ENGINE;
import static org.sonar.api.SonarQubeSide.SERVER;

public class ItTestsCoreExtension implements CoreExtension {
  @Override
  public String getName() {
    return "it-tests";
  }

  @Override
  public void load(Context context) {
    // Nothing should be loaded when the plugin is running within by the scanner
    SonarQubeSide sonarQubeSide = context.getRuntime().getSonarQubeSide();
    if (sonarQubeSide == COMPUTE_ENGINE || sonarQubeSide == SERVER) {
      context.addExtension(FakeWorkerCountProviderImpl.class);
      context.addExtension(WorkerLatchMetrics.class);
      context.addExtension(LatchControllerWorkerMeasureComputer.class);
      context.addExtension(RefreshWorkerCountAction.class);
      context.addExtension(SystemPasscodeWebService.class);

      // WS api/fake_gov
      context.addExtension(FakeGovWs.class);

      // failing CE tasks
      context.addExtension(SubmitAction.class);
      context.addExtension(OomTaskProcessor.class);
      context.addExtension(IseTaskProcessor.class);
      context.addExtension(OkTaskProcessor.class);

      // component bombs injection into the Report Analysis processing container in the CE
      context.addExtension(BombConfig.class);
      context.addExtension(ComponentBombReportAnalysisComponentProvider.class);
      context.addExtension(BombActivatorAction.class);
    }
  }

}
