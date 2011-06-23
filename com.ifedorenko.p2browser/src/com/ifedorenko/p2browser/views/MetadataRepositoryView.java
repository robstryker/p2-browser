package com.ifedorenko.p2browser.views;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.part.ViewPart;

import com.ifedorenko.p2browser.Activator;
import com.ifedorenko.p2browser.dialogs.RepositoryLocationDialog;
import com.ifedorenko.p2browser.model.IGroupedInstallableUnits;
import com.ifedorenko.p2browser.model.IncludedInstallableUnits;
import com.ifedorenko.p2browser.model.UngroupedInstallableUnits;
import org.eclipse.swt.widgets.Label;

@SuppressWarnings( "restriction" )
public class MetadataRepositoryView
    extends ViewPart
{

    public static final String ID = "com.ifedorenko.p2browser.views.MetadataRepositoryView2"; //$NON-NLS-1$

    private final FormToolkit toolkit = new FormToolkit( Display.getCurrent() );

    /**
     * Root repositories explicitly added by the user
     */
    private Map<URI, IMetadataRepository> repositories = new LinkedHashMap<URI, IMetadataRepository>();

    /**
     * All repositories, including children of composite repositories
     */
    private Map<URI, IMetadataRepository> allrepositories = new LinkedHashMap<URI, IMetadataRepository>();

    private Map<URI, IGroupedInstallableUnits> repositoryContent = new HashMap<URI, IGroupedInstallableUnits>();

    private boolean revealCompositeRepositories = true;

    private boolean groupIncludedIUs = true;

    private TreeViewer treeViewer;

    private Text filterText;

    private final InstallableUnitFilter filter = new InstallableUnitFilter();

    private Job refreshTreeJob = new Job( "Refresh" )
    {
        @Override
        protected IStatus run( IProgressMonitor monitor )
        {
            refreshTreeInDisplayThread();

            return Status.OK_STATUS;
        }
    };

    public MetadataRepositoryView()
    {
    }

    @Override
    public void createPartControl( Composite parent )
    {
        FillLayout fillLayout = (FillLayout) parent.getLayout();
        fillLayout.type = SWT.VERTICAL;
        Composite container = toolkit.createComposite( parent, SWT.NONE );
        toolkit.paintBordersFor( container );
        GridLayout gl_container = new GridLayout( 2, false );
        gl_container.marginHeight = 0;
        gl_container.marginWidth = 0;
        container.setLayout( gl_container );
        {
            Composite filterComposite = new Composite( container, SWT.NONE );
            filterComposite.setLayout( new GridLayout( 2, false ) );
            filterComposite.setLayoutData( new GridData( SWT.FILL, SWT.FILL, false, false, 1, 1 ) );
            toolkit.adapt( filterComposite );
            toolkit.paintBordersFor( filterComposite );
            final Combo filterType = new Combo( filterComposite, SWT.READ_ONLY );
            filterType.setItems( new String[] { "Filter by IU", "Filter by capability" } );
            toolkit.adapt( filterType );
            toolkit.paintBordersFor( filterType );
            filterType.setText( "Filter by IU" );
            filterText = new Text( filterComposite, SWT.BORDER | SWT.H_SCROLL | SWT.SEARCH | SWT.CANCEL );
            filterText.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
            toolkit.adapt( filterText, true, true );
            ModifyListener filterChangeListener = new ModifyListener()
            {
                public void modifyText( ModifyEvent e )
                {
                    InstallableUnitFilter.FilterMode mode = InstallableUnitFilter.FilterMode.iu;
                    switch ( filterType.getSelectionIndex() )
                    {
                        case 1:
                            mode = InstallableUnitFilter.FilterMode.capability;
                            break;
                        case 0:
                        default:
                            mode = InstallableUnitFilter.FilterMode.iu;
                            break;
                    }
                    String pattern = trim( filterText.getText() );
                    filter.setFilter( mode, pattern );
                    refreshTreeJob.schedule( 500L );
                }
            };
            filterText.addModifyListener( filterChangeListener );
            filterType.addModifyListener( filterChangeListener );
        }
        {
            Composite composite = new Composite( container, SWT.NONE );
            composite.setLayoutData( new GridData( SWT.LEFT, SWT.FILL, false, true, 1, 2 ) );
            toolkit.adapt( composite );
            toolkit.paintBordersFor( composite );
            GridLayout gl_composite = new GridLayout( 1, false );
            gl_composite.marginHeight = 0;
            gl_composite.marginWidth = 0;
            composite.setLayout( gl_composite );
            {
                Label lblView = new Label(composite, SWT.NONE);
                toolkit.adapt(lblView, true, true);
                lblView.setText("Repositories");
            }
            {
                Button btnAdd = new Button( composite, SWT.NONE );
                btnAdd.addSelectionListener( new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected( SelectionEvent e )
                    {
                        RepositoryLocationDialog dialog = new RepositoryLocationDialog( getSite().getShell() );
                        if ( dialog.open() == IDialogConstants.OK_ID )
                        {
                            addRepository( dialog.getLocation() );
                        }
                    }
                } );
                GridData gd_btnAdd = new GridData( SWT.LEFT, SWT.TOP, false, false, 1, 1 );
                gd_btnAdd.horizontalIndent = 10;
                btnAdd.setLayoutData( gd_btnAdd );
                toolkit.adapt( btnAdd, true, true );
                btnAdd.setText( "Add..." );
            }
            {
                Button btnRemove = new Button( composite, SWT.NONE );
                GridData gd_btnRemove = new GridData(SWT.LEFT, SWT.CENTER, false, false, 1, 1);
                gd_btnRemove.horizontalIndent = 10;
                btnRemove.setLayoutData(gd_btnRemove);
                toolkit.adapt( btnRemove, true, true );
                btnRemove.setText( "Remove" );
            }
            {
                Label lblView = new Label(composite, SWT.NONE);
                toolkit.adapt(lblView, true, true);
                lblView.setText("View");
            }
            {
                final Button btnGroupInlcuded = new Button( composite, SWT.CHECK );
                GridData gd_btnGroupInlcuded = new GridData(SWT.LEFT, SWT.CENTER, false, false, 1, 1);
                gd_btnGroupInlcuded.horizontalIndent = 10;
                btnGroupInlcuded.setLayoutData(gd_btnGroupInlcuded);
                btnGroupInlcuded.addSelectionListener( new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected( SelectionEvent e )
                    {
                        groupIncludedIUs = btnGroupInlcuded.getSelection();
                        repositoryContent.clear();
                        treeViewer.refresh();
                    }
                } );
                btnGroupInlcuded.setSelection( groupIncludedIUs );
                toolkit.adapt( btnGroupInlcuded, true, true );
                btnGroupInlcuded.setText( "Group inlcuded" );
            }
            {
                final Button btnChildRepositories = new Button( composite, SWT.CHECK );
                GridData gd_btnChildRepositories = new GridData(SWT.LEFT, SWT.CENTER, false, false, 1, 1);
                gd_btnChildRepositories.horizontalIndent = 10;
                btnChildRepositories.setLayoutData(gd_btnChildRepositories);
                btnChildRepositories.setToolTipText( "Reveal composite repository structure" );
                btnChildRepositories.addSelectionListener( new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected( SelectionEvent e )
                    {
                        revealCompositeRepositories = btnChildRepositories.getSelection();
                        treeViewer.refresh();
                    }
                } );
                btnChildRepositories.setSelection( revealCompositeRepositories );
                toolkit.adapt( btnChildRepositories, true, true );
                btnChildRepositories.setText( "Child repositories" );
            }
        }
        {
            treeViewer = new TreeViewer( container, SWT.BORDER | SWT.MULTI );
            treeViewer.setUseHashlookup( true );
            treeViewer.setSorter( new InstallableUnitSorter() );
            treeViewer.addFilter( filter );
            Tree tree = treeViewer.getTree();
            tree.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true, 1, 1 ) );
            tree.setLinesVisible( true );
            treeViewer.setLabelProvider( new InstallableUnitLabelProvider()
            {
                @Override
                public String getText( Object element )
                {
                    if ( element instanceof IRepository<?> )
                    {
                        return ( (IRepository<?>) element ).getLocation().toString();
                    }
                    return super.getText( element );
                }
            } );
            treeViewer.setContentProvider( new InstallableUnitContentProvider()
            {
                @Override
                public Object[] getElements( Object inputElement )
                {
                    if ( inputElement instanceof Map<?, ?> )
                    {
                        return ( (Map<?, ?>) inputElement ).values().toArray();
                    }
                    return null;
                }

                @Override
                public Object[] getChildren( Object parentElement )
                {
                    if ( revealCompositeRepositories && parentElement instanceof CompositeMetadataRepository )
                    {
                        return getImmediateChildren( (CompositeMetadataRepository) parentElement );
                    }
                    else if ( parentElement instanceof IMetadataRepository )
                    {
                        final IMetadataRepository repo = (IMetadataRepository) parentElement;
                        final IGroupedInstallableUnits[] content =
                            new IGroupedInstallableUnits[] { repositoryContent.get( repo.getLocation() ) };
                        if ( content[0] == null )
                        {
                            try
                            {
                                getSite().getWorkbenchWindow().run( true, true, new IRunnableWithProgress()
                                {
                                    @Override
                                    public void run( IProgressMonitor monitor )
                                        throws InvocationTargetException, InterruptedException
                                    {
                                        if ( groupIncludedIUs )
                                        {
                                            content[0] = IncludedInstallableUnits.getInstallableUnits( repo, monitor );
                                        }
                                        else
                                        {
                                            content[0] = UngroupedInstallableUnits.getInstallableUnits( repo, monitor );
                                        }
                                    }

                                } );
                                repositoryContent.put( repo.getLocation(), content[0] );
                            }
                            catch ( InvocationTargetException e )
                            {
                                handleException( "Could not load repository metadata", e );
                                return new Object[0];
                            }
                            catch ( InterruptedException e )
                            {
                                return new Object[0];
                            }

                        }
                        return toViewNodes( content[0], content[0].getRootIncludedInstallableUnits() );
                    }
                    return super.getChildren( parentElement );
                }
            } );
            treeViewer.setInput( repositories );
            toolkit.paintBordersFor( tree );
        }

        new InstallableUnitTreeActions( getViewSite(), treeViewer )
        {
            @Override
            protected IQueryable<IInstallableUnit> getAllInstallableUnits()
            {
                return toQueryable( repositories.values() );
            }
        };
    }

    private IQueryable<IInstallableUnit> toQueryable( Collection<IMetadataRepository> repositories )
    {
        return QueryUtil.compoundQueryable( repositories );
    }

    protected void addRepository( final URI location )
    {
        Job job = new Job( "Load repository metadata" )
        {
            @Override
            protected IStatus run( IProgressMonitor monitor )
            {
                try
                {
                    IMetadataRepositoryManager repoMgr = Activator.getRepositoryManager();

                    IMetadataRepository repository = repoMgr.loadRepository( location, monitor );

                    if ( repository != null )
                    {
                        repositories.put( location, repository );
                        allrepositories.put( location, repository );

                        refreshTreeInDisplayThread();
                    }

                    return Status.OK_STATUS;
                }
                catch ( ProvisionException e )
                {
                    return e.getStatus();
                }
                catch ( OperationCanceledException e )
                {
                    return Status.CANCEL_STATUS;
                }
            }
        };
        job.setUser( true );
        job.schedule();
    }

    private void handleException( String title, InvocationTargetException e )
    {
        String message = e.getMessage();
        new ErrorDialog( getSite().getShell(), title, message, null, IStatus.ERROR ).open();
    }

    protected Object[] getImmediateChildren( CompositeMetadataRepository repository )
    {
        List<IMetadataRepository> result = new ArrayList<IMetadataRepository>();
        final List<URI> missing = new ArrayList<URI>();

        for ( URI childUri : repository.getChildren() )
        {
            IMetadataRepository child = allrepositories.get( childUri );
            if ( child != null )
            {
                result.add( child );
            }
            else
            {
                missing.add( childUri );
            }
        }

        if ( !missing.isEmpty() )
        {
            Job job = new Job( "Load child repository metadata" )
            {
                @Override
                protected IStatus run( IProgressMonitor monitor )
                {
                    IMetadataRepositoryManager repoMgr = Activator.getRepositoryManager();

                    List<IStatus> errors = new ArrayList<IStatus>();

                    for ( URI location : missing )
                    {
                        try
                        {
                            IMetadataRepository repository = repoMgr.loadRepository( location, monitor );

                            allrepositories.put( location, repository );
                        }
                        catch ( ProvisionException e )
                        {
                            errors.add( e.getStatus() );
                        }
                        catch ( OperationCanceledException e )
                        {
                            return Status.CANCEL_STATUS;
                        }
                    }

                    refreshTreeInDisplayThread();

                    if ( !errors.isEmpty() )
                    {
                        MultiStatus status =
                            new MultiStatus( Activator.PLUGIN_ID, -1, "Could not load child repository metadata", null );
                        for ( IStatus error : errors )
                        {
                            status.add( error );
                        }
                        return status;
                    }

                    return Status.OK_STATUS;
                }
            };
            job.setUser( true );
            job.schedule();
        }

        return result.toArray();
    }

    public void dispose()
    {
        toolkit.dispose();
        super.dispose();
    }

    @Override
    public void setFocus()
    {
        // Set the focus
    }

    private void refreshTreeInDisplayThread()
    {
        getSite().getShell().getDisplay().asyncExec( new Runnable()
        {
            @Override
            public void run()
            {
                treeViewer.refresh();
            }
        } );
    }

    private static String trim( String str )
    {
        if ( str == null )
        {
            return null;
        }
        str = str.trim();
        if ( "".equals( str ) )
        {
            return null;
        }
        return str;
    }

}
