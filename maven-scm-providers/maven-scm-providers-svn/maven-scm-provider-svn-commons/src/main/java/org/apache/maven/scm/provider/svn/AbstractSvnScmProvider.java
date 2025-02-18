/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.scm.provider.svn;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.scm.CommandParameters;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmResult;
import org.apache.maven.scm.command.add.AddScmResult;
import org.apache.maven.scm.command.blame.BlameScmResult;
import org.apache.maven.scm.command.branch.BranchScmResult;
import org.apache.maven.scm.command.changelog.ChangeLogScmResult;
import org.apache.maven.scm.command.checkin.CheckInScmResult;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.command.diff.DiffScmResult;
import org.apache.maven.scm.command.export.ExportScmResult;
import org.apache.maven.scm.command.info.InfoItem;
import org.apache.maven.scm.command.info.InfoScmResult;
import org.apache.maven.scm.command.list.ListScmResult;
import org.apache.maven.scm.command.mkdir.MkdirScmResult;
import org.apache.maven.scm.command.remove.RemoveScmResult;
import org.apache.maven.scm.command.status.StatusScmResult;
import org.apache.maven.scm.command.tag.TagScmResult;
import org.apache.maven.scm.command.untag.UntagScmResult;
import org.apache.maven.scm.command.update.UpdateScmResult;
import org.apache.maven.scm.provider.AbstractScmProvider;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.provider.svn.command.SvnCommand;
import org.apache.maven.scm.provider.svn.repository.SvnScmProviderRepository;
import org.apache.maven.scm.provider.svn.util.SvnUtil;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.scm.repository.UnknownRepositoryStructure;
import org.codehaus.plexus.util.StringUtils;

/**
 * SCM Provider for Subversion
 *
 * @author <a href="mailto:evenisse@apache.org">Emmanuel Venisse</a>
 *
 */
public abstract class AbstractSvnScmProvider extends AbstractScmProvider {
    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    private static class ScmUrlParserResult {
        private final List<String> messages = new ArrayList<>();

        private ScmProviderRepository repository;
    }

    public static final String CURRENT_WORKING_DIRECTORY = "scmCheckWorkingDirectoryUrl.currentWorkingDirectory";

    // ----------------------------------------------------------------------
    // ScmProvider Implementation
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public String getScmSpecificFilename() {
        return ".svn";
    }

    /**
     * {@inheritDoc}
     */
    public ScmProviderRepository makeProviderScmRepository(String scmSpecificUrl, char delimiter)
            throws ScmRepositoryException {
        ScmUrlParserResult result = parseScmUrl(scmSpecificUrl);

        if (checkCurrentWorkingDirectoryUrl()) {
            logger.debug("Checking svn info 'URL:' field matches current sources directory");
            try {
                String workingDir = System.getProperty(CURRENT_WORKING_DIRECTORY);
                InfoScmResult info =
                        info(result.repository, new ScmFileSet(new File(workingDir)), new CommandParameters());

                String url = findUrlInfoItem(info);
                String comparison = "'" + url + "' vs. '" + scmSpecificUrl + "'";
                logger.debug("Comparing : " + comparison);
                if (url != null && !url.equals(scmSpecificUrl)) {
                    result.messages.add("Scm url does not match the value returned by svn info (" + comparison + ")");
                }
            } catch (ScmException e) {
                throw new ScmRepositoryException("An error occurred while trying to svn info", e);
            }
        }
        if (result.messages.size() > 0) {
            throw new ScmRepositoryException("The scm url is invalid.", result.messages);
        }

        return result.repository;
    }

    private boolean checkCurrentWorkingDirectoryUrl() {
        return StringUtils.isNotEmpty(System.getProperty(CURRENT_WORKING_DIRECTORY));
    }

    private String findUrlInfoItem(InfoScmResult infoScmResult) {
        for (InfoItem infoItem : infoScmResult.getInfoItems()) {
            if (infoItem.getURL() != null) {
                logger.debug("URL found: " + infoItem.getURL());
                return infoItem.getURL();
            }
        }
        logger.debug("URL not found (command output=" + infoScmResult.getCommandOutput() + ")");
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public ScmProviderRepository makeProviderScmRepository(File path)
            throws ScmRepositoryException, UnknownRepositoryStructure {
        if (path == null) {
            throw new NullPointerException("Path argument is null");
        }

        if (!path.isDirectory()) {
            throw new ScmRepositoryException(path.getAbsolutePath() + " isn't a valid directory.");
        }

        if (!new File(path, ".svn").exists()) {
            throw new ScmRepositoryException(path.getAbsolutePath() + " isn't a svn checkout directory.");
        }

        try {
            return makeProviderScmRepository(getRepositoryURL(path), ':');
        } catch (ScmException e) {
            // XXX We should allow throwing of SCMException.
            throw new ScmRepositoryException("Error executing info command", e);
        }
    }

    protected abstract String getRepositoryURL(File path) throws ScmException;

    /**
     * {@inheritDoc}
     */
    public List<String> validateScmUrl(String scmSpecificUrl, char delimiter) {
        List<String> messages = new ArrayList<>();
        try {
            makeProviderScmRepository(scmSpecificUrl, delimiter);
        } catch (ScmRepositoryException e) {
            messages = e.getValidationMessages();
        }
        return messages;
    }

    /**
     * {@inheritDoc}
     */
    public String getScmType() {
        return "svn";
    }

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    private ScmUrlParserResult parseScmUrl(String scmSpecificUrl) {
        ScmUrlParserResult result = new ScmUrlParserResult();

        // ----------------------------------------------------------------------
        // Do some sanity checking of the SVN url
        // ----------------------------------------------------------------------

        if (scmSpecificUrl.startsWith("file")) {
            if (!scmSpecificUrl.startsWith("file://")) {
                result.messages.add("A svn 'file' url must be on the form 'file://[hostname]/'.");

                return result;
            }
        } else if (scmSpecificUrl.startsWith("https")) {
            if (!scmSpecificUrl.startsWith("https://")) {
                result.messages.add("A svn 'http' url must be on the form 'https://'.");

                return result;
            }
        } else if (scmSpecificUrl.startsWith("http")) {
            if (!scmSpecificUrl.startsWith("http://")) {
                result.messages.add("A svn 'http' url must be on the form 'http://'.");

                return result;
            }
        }
        // Support of tunnels: svn+xxx with xxx defined in subversion conf file
        else if (scmSpecificUrl.startsWith("svn+")) {
            if (scmSpecificUrl.indexOf("://") < 0) {
                result.messages.add("A svn 'svn+xxx' url must be on the form 'svn+xxx://'.");

                return result;
            } else {
                String tunnel = scmSpecificUrl.substring("svn+".length(), scmSpecificUrl.indexOf("://"));

                // ssh is always an allowed tunnel
                if (!"ssh".equals(tunnel)) {
                    SvnConfigFileReader reader = new SvnConfigFileReader();
                    if (SvnUtil.getSettings().getConfigDirectory() != null) {
                        reader.setConfigDirectory(new File(SvnUtil.getSettings().getConfigDirectory()));
                    }

                    if (StringUtils.isEmpty(reader.getProperty("tunnels", tunnel))) {
                        result.messages.add(
                                "The tunnel '" + tunnel + "' isn't defined in your subversion configuration file.");

                        return result;
                    }
                }
            }
        } else if (scmSpecificUrl.startsWith("svn")) {
            if (!scmSpecificUrl.startsWith("svn://")) {
                result.messages.add("A svn 'svn' url must be on the form 'svn://'.");

                return result;
            }
        } else {
            result.messages.add(scmSpecificUrl + " url isn't a valid svn URL.");

            return result;
        }

        result.repository = new SvnScmProviderRepository(scmSpecificUrl);

        return result;
    }

    protected abstract SvnCommand getAddCommand();

    /**
     * {@inheritDoc}
     */
    public AddScmResult add(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (AddScmResult) executeCommand(getAddCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getBranchCommand();

    /**
     * {@inheritDoc}
     */
    protected BranchScmResult branch(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (BranchScmResult) executeCommand(getBranchCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getChangeLogCommand();

    /**
     * {@inheritDoc}
     */
    public ChangeLogScmResult changelog(
            ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters) throws ScmException {
        return (ChangeLogScmResult) executeCommand(getChangeLogCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getCheckInCommand();

    /**
     * {@inheritDoc}
     */
    public CheckInScmResult checkin(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (CheckInScmResult) executeCommand(getCheckInCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getCheckOutCommand();

    /**
     * {@inheritDoc}
     */
    public CheckOutScmResult checkout(
            ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters) throws ScmException {
        return (CheckOutScmResult) executeCommand(getCheckOutCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getDiffCommand();

    /**
     * {@inheritDoc}
     */
    public DiffScmResult diff(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (DiffScmResult) executeCommand(getDiffCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getExportCommand();

    /**
     * {@inheritDoc}
     */
    protected ExportScmResult export(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (ExportScmResult) executeCommand(getExportCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getRemoveCommand();

    /**
     * {@inheritDoc}
     */
    public RemoveScmResult remove(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (RemoveScmResult) executeCommand(getRemoveCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getStatusCommand();

    /**
     * {@inheritDoc}
     */
    public StatusScmResult status(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (StatusScmResult) executeCommand(getStatusCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getTagCommand();

    /**
     * {@inheritDoc}
     */
    public TagScmResult tag(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (TagScmResult) executeCommand(getTagCommand(), repository, fileSet, parameters);
    }

    protected abstract SvnCommand getUntagCommand();

    /**
     * {@inheritDoc}
     */
    @Override
    public UntagScmResult untag(ScmRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (UntagScmResult)
                executeCommand(getUntagCommand(), repository.getProviderRepository(), fileSet, parameters);
    }

    protected abstract SvnCommand getUpdateCommand();

    /**
     * {@inheritDoc}
     */
    public UpdateScmResult update(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return (UpdateScmResult) executeCommand(getUpdateCommand(), repository, fileSet, parameters);
    }

    protected ScmResult executeCommand(
            SvnCommand command, ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return command.execute(repository, fileSet, parameters);
    }

    protected abstract SvnCommand getListCommand();

    /**
     * {@inheritDoc}
     */
    public ListScmResult list(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        SvnCommand cmd = getListCommand();

        return (ListScmResult) executeCommand(cmd, repository, fileSet, parameters);
    }

    protected abstract SvnCommand getInfoCommand();

    public InfoScmResult info(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        SvnCommand cmd = getInfoCommand();

        return (InfoScmResult) executeCommand(cmd, repository, fileSet, parameters);
    }

    /**
     * {@inheritDoc}
     */
    protected BlameScmResult blame(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        SvnCommand cmd = getBlameCommand();

        return (BlameScmResult) executeCommand(cmd, repository, fileSet, parameters);
    }

    protected abstract SvnCommand getBlameCommand();

    /**
     * {@inheritDoc}
     */
    public MkdirScmResult mkdir(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        SvnCommand cmd = getMkdirCommand();

        return (MkdirScmResult) executeCommand(cmd, repository, fileSet, parameters);
    }

    protected abstract SvnCommand getMkdirCommand();

    /**
     * @param repository
     * @param parameters
     * @return true if remote url exists
     * @throws ScmException
     * @since 1.8
     */
    public abstract boolean remoteUrlExist(ScmProviderRepository repository, CommandParameters parameters)
            throws ScmException;
}
