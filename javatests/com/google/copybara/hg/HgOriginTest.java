/*
 * Copyright (C) 2018 Google Inc.
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

package com.google.copybara.hg;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.copybara.Origin.Reader;
import com.google.copybara.authoring.Author;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.authoring.Authoring.AuthoringMappingMode;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.hg.HgRepository.HgLogEntry;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.util.Glob;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HgOriginTest {

  private final Authoring authoring = new Authoring(new Author("Copy",
      "copy@bara.com"),
      AuthoringMappingMode.PASS_THRU, ImmutableSet.of());

  private HgOrigin origin;
  private OptionsBuilder options;
  private SkylarkTestExecutor skylark;
  private Glob originFiles;
  private HgRepository repository;
  private Path remotePath;
  private String url;

  @Before
  public void setup() throws Exception {
    options = new OptionsBuilder().setOutputRootToTmpDir();
    skylark = new SkylarkTestExecutor(options);
    originFiles = Glob.ALL_FILES;

    remotePath = Files.createTempDirectory("remote");
    url = remotePath.toAbsolutePath().toString();

    origin = skylark.eval("result",
        String.format("result = hg.origin( url = '%s' )", url));

    repository = new HgRepository(remotePath);
    repository.init();
  }

  private Reader<HgRevision> newReader() {
    return origin.newReader(originFiles, authoring);
  }

  private HgOrigin origin() throws ValidationException {
    return skylark.eval("result",
        String.format("result = hg.origin(\n"
            + "    url = '%s',\n"
            + ")", url));
  }

  @Test
  public void testHgOrigin() throws Exception {
    origin = skylark.eval("result",
        "result = hg.origin(\n"
            + "url = 'https://my-server.org/copybara'"
            + ")");

    assertThat(origin.getLabelName())
        .isEqualTo("HgOrigin{"
          + "url = https://my-server.org/copybara"
          + "}");
  }

  @Test
  public void testEmptyUrl() throws Exception {
    skylark.evalFails("hg.origin(url = '')", "Invalid empty field 'url'");
  }

  @Test
  public void testCheckout() throws Exception {
    Reader<HgRevision> reader = newReader();

    Path workDir = Files.createTempDirectory("workDir");

    Path newFile = Files.createTempFile(remotePath, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(remotePath, "add", fileName);
    repository.hg(remotePath, "commit", "-m", "foo");

    Files.write(remotePath.resolve(fileName), "hello".getBytes(UTF_8));
    repository.hg(remotePath, "commit", "-m", "hello");

    Files.write(remotePath.resolve(fileName), "goodbye".getBytes(UTF_8));
    repository.hg(remotePath, "commit", "-m", "bye");

    repository.hg(remotePath, "rm", fileName);
    repository.hg(remotePath, "commit", "-m", "rm foo");

    ImmutableList<HgLogEntry> commits = repository.log().run();

    reader.checkout(origin.resolve(commits.get(2).getGlobalId()), workDir);
    assertThat(new String(Files.readAllBytes(newFile))).isEqualTo("hello");

    reader.checkout(origin.resolve(commits.get(1).getGlobalId()), workDir);
    assertThat(new String(Files.readAllBytes(newFile))).isEqualTo("goodbye");

    File archive = new File(workDir.toString());
    assertThat(archive.listFiles()).hasLength(2);

    reader.checkout(origin.resolve(commits.get(0).getGlobalId()), workDir);
    assertThat(Files.exists(newFile)).isFalse();

    assertThat(archive.listFiles()).hasLength(0);
  }
}