package de.weingardt.gitlab.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mylyn.commons.net.Policy;
import org.eclipse.mylyn.tasks.core.ITaskMapping;
import org.eclipse.mylyn.tasks.core.RepositoryResponse;
import org.eclipse.mylyn.tasks.core.RepositoryResponse.ResponseKind;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMapper;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMetaData;
import org.eclipse.mylyn.tasks.core.data.TaskCommentMapper;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabIssue;
import org.gitlab.api.models.GitlabNote;

import de.weingardt.gitlab.core.exceptions.GitlabException;

public class GitlabTaskDataHandler extends AbstractTaskDataHandler {
	
	private GitlabConnector connector;
	
	public GitlabTaskDataHandler(GitlabConnector gitlabConnector) {
		this.connector = gitlabConnector;
	}

	@Override
	public TaskAttributeMapper getAttributeMapper(TaskRepository repository) {
		try {
			return GitlabPlugin.get().getConnector().get(repository).mapper;
		} catch (CoreException e) {
			return new GitlabAttributeMapper(repository);
		}
	}

	@Override
	public boolean initializeTaskData(TaskRepository repository, TaskData data,
			ITaskMapping mapping, IProgressMonitor monitor) throws CoreException {
		createDefaultAttributes(data, false);
		
		GitlabConnection connection = GitlabPlugin.get().getConnector().get(repository);
		TaskAttribute root = data.getRoot();

		root.getAttribute(GitlabAttribute.PROJECT.getTaskKey()).setValue(connection.project.getName());
		root.getAttribute(GitlabAttribute.LABELS.getTaskKey()).setValue("");
		root.getAttribute(GitlabAttribute.STATUS.getTaskKey()).setValue("open");
		
		return true;
	}

	@Override
	public RepositoryResponse postTaskData(TaskRepository repository, TaskData data,
			Set<TaskAttribute> attributes, IProgressMonitor monitor)
			throws CoreException {
		
		TaskAttribute root = data.getRoot();
		String labels = root.getAttribute(GitlabAttribute.LABELS.getTaskKey()).getValue();
		String title = root.getAttribute(GitlabAttribute.TITLE.getTaskKey()).getValue();
		String body = root.getAttribute(GitlabAttribute.BODY.getTaskKey()).getValue();
		
		GitlabConnection connection = connector.get(repository);
		GitlabAPI api = connection.api();
		
		try {
			GitlabIssue issue = null;
			if(data.isNew()) {
				issue = api.createIssue(connection.project.getId(), 0, 0, labels, body, title);
				return new RepositoryResponse(ResponseKind.TASK_CREATED, "" + issue.getId());
			} else {

				if(root.getAttribute(TaskAttribute.COMMENT_NEW) != null && 
						!root.getAttribute(TaskAttribute.COMMENT_NEW).getValue().equals("")) {
					api.createNote(connection.project.getId(), GitlabConnector.getTicketId(data.getTaskId()), 
							root.getAttribute(TaskAttribute.COMMENT_NEW).getValue());
				}
				
				issue = api.editIssue(connection.project.getId(), GitlabConnector.getTicketId(data.getTaskId()), 0, 
						0, labels, body, title, GitlabIssue.Action.LEAVE);
				return new RepositoryResponse(ResponseKind.TASK_UPDATED, "" + issue.getId());
			}
		} catch (IOException e) {
			throw new GitlabException("Unknown connection error!");
		}
	}

	public TaskData getTaskData(TaskRepository repository, String id,
			IProgressMonitor monitor) throws CoreException {
		monitor = Policy.monitorFor(monitor);
		try {
			monitor.beginTask("Task Download", IProgressMonitor.UNKNOWN); //$NON-NLS-1$
			return downloadTaskData(repository, GitlabConnector.getTicketId(id));
		} finally {
			monitor.done();
		}
	}

	public TaskData downloadTaskData(TaskRepository repository, Integer ticketId) throws CoreException {
		try {
			GitlabConnection connection = connector.get(repository);
			GitlabAPI api = connection.api();
			GitlabIssue issue = api.getIssue(connection.project.getId(), ticketId);
			List<GitlabNote> notes = api.getNotes(issue);

			return createTaskDataFromGitlabIssue(issue, repository, notes);
		} catch (IOException e) {
			throw new GitlabException("Unknown connection error!");
		}
	}
	
	public TaskData createTaskDataFromGitlabIssue(GitlabIssue issue, TaskRepository repository) throws CoreException {
		return createTaskDataFromGitlabIssue(issue, repository, new ArrayList<GitlabNote>());
	}
	
	public TaskData createTaskDataFromGitlabIssue(GitlabIssue issue, TaskRepository repository, 
			List<GitlabNote> notes) throws CoreException {
		GitlabConnection connection = connector.get(repository);
		TaskData data = new TaskData(connection.mapper, GitlabPlugin.CONNECTOR_KIND, repository.getUrl(), 
				"" + issue.getId());
		
		createDefaultAttributes(data, true);
		
		TaskAttribute root = data.getRoot();
		root.getAttribute(GitlabAttribute.AUTHOR.getTaskKey()).setValue(issue.getAuthor().getName());
		root.getAttribute(GitlabAttribute.CREATED.getTaskKey()).setValue("" + issue.getCreatedAt().getTime());
		root.getAttribute(GitlabAttribute.BODY.getTaskKey()).setValue(issue.getDescription());
		root.getAttribute(GitlabAttribute.LABELS.getTaskKey()).setValue(StringUtils.join(issue.getLabels(), ", "));
		root.getAttribute(GitlabAttribute.PROJECT.getTaskKey()).setValue(connection.project.getName());
		root.getAttribute(GitlabAttribute.STATUS.getTaskKey()).setValue(issue.getState());
		root.getAttribute(GitlabAttribute.TITLE.getTaskKey()).setValue(issue.getTitle());
		
		root.getAttribute(GitlabAttribute.IID.getTaskKey()).setValue("" + issue.getIid());
		
		if(issue.getUpdatedAt() != null) {
			root.getAttribute(GitlabAttribute.UPDATED.getTaskKey()).setValue("" + issue.getUpdatedAt().getTime());
		}
		
		if(issue.getState().equals(GitlabIssue.StateClosed)) {
			root.getAttribute(GitlabAttribute.COMPLETED.getTaskKey()).setValue("" + issue.getUpdatedAt().getTime());
		}
		
		if(issue.getAssignee() != null) {
			root.getAttribute(GitlabAttribute.ASSIGNEE.getTaskKey()).setValue(issue.getAssignee().getName());
		}
		
		Collections.sort(notes, new Comparator<GitlabNote>() {
			@Override
			public int compare(GitlabNote o1, GitlabNote o2) {
				return o1.getCreatedAt().compareTo(o2.getCreatedAt());
			}
		});
		
		for (int i = 0; i < notes.size(); i++) {
			TaskCommentMapper cmapper = new TaskCommentMapper();
			cmapper.setAuthor(repository.createPerson(notes.get(i).getAuthor().getName()));
			cmapper.setCreationDate(notes.get(i).getCreatedAt());
			cmapper.setText(notes.get(i).getBody());
			cmapper.setNumber(i + 1);
			TaskAttribute attribute = data.getRoot().createAttribute(TaskAttribute.PREFIX_COMMENT + (i + 1));
			cmapper.applyTo(attribute);
		}
		
		return data;
	}
	
	private void createDefaultAttributes(TaskData data, boolean existingTask) {
		createAttribute(data, GitlabAttribute.BODY);
		createAttribute(data, GitlabAttribute.TITLE);
		createAttribute(data, GitlabAttribute.LABELS);
		createAttribute(data, GitlabAttribute.STATUS);
		createAttribute(data, GitlabAttribute.PROJECT);

		createAttribute(data, GitlabAttribute.CREATED);
		createAttribute(data, GitlabAttribute.COMPLETED);
		createAttribute(data, GitlabAttribute.UPDATED);
		createAttribute(data, GitlabAttribute.ASSIGNEE);
		
		createAttribute(data, GitlabAttribute.IID);

		data.getRoot().getAttribute(GitlabAttribute.CREATED.getTaskKey()).setValue("" + (new Date().getTime()));

		if(existingTask) {
			data.getRoot().createAttribute(TaskAttribute.COMMENT_NEW)
				.getMetaData().setType(TaskAttribute.TYPE_LONG_RICH_TEXT)
				.setReadOnly(false);

			createAttribute(data, GitlabAttribute.AUTHOR);
		}

		TaskAttribute operation = data.getRoot().createAttribute(TaskAttribute.OPERATION);
		operation.getMetaData().setType(TaskAttribute.TYPE_OPERATION);
	}
	
	private void createAttribute(TaskData data, GitlabAttribute attribute) {
		TaskAttribute attr = data.getRoot().createAttribute(attribute.getTaskKey());
		TaskAttributeMetaData metaData = attr.getMetaData();
		metaData.setType(attribute.getType());
		metaData.setKind(attribute.getKind());
		metaData.setLabel(attribute.toString());
		metaData.setReadOnly(attribute.isReadOnly());
	}

}
