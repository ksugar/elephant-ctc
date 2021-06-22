package org.elephant;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.mastodon.collection.RefCollections;
import org.mastodon.collection.RefList;
import org.mastodon.kdtree.IncrementalNearestNeighborSearch;
import org.mastodon.pool.PoolCollectionWrapper;
import org.mastodon.revised.model.mamut.Link;
import org.mastodon.revised.model.mamut.Model;
import org.mastodon.revised.model.mamut.ModelGraph;
import org.mastodon.revised.model.mamut.Spot;
import org.mastodon.spatial.SpatialIndex;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.opencsv.CSVWriter;

import net.imglib2.RealPoint;

public class ElephantCTC
{
	private static final int MINIMUM_ID = 1;

	private static final int UNSET = -1;

	private static final String RES_FILENAME = "res_track.txt";

	private static int id;

	public static void main( String[] args )
	{
		if ( args.length != 3 )
		{
			System.out.println( "The following arguments are required: input, output, config" );
			System.exit( 0 );
		}
		for ( final String arg : args )
			System.out.println( arg );
		try
		{
			// Load config
			final JsonObject config = Json.parse( readString( args[ 2 ] ) ).asObject();

			final String spotsFileName = UUID.randomUUID().toString() + ".json";

			// Detection
			final ProcessBuilder pb = new ProcessBuilder().inheritIO();
			pb.environment().put( "CTC", "1" );
			pb.command(
					"./run_elephant.sh", "detection",
					args[ 0 ],
					args[ 2 ],
					spotsFileName ).start().waitFor();
			String jsonString = null;
			try
			{
				jsonString = readString( spotsFileName );
			}
			catch ( final NoSuchFileException e )
			{
				System.out.println( "detection failed" );
				System.exit( 1 );
			}
			System.out.println( "detection completed" );

			final JsonArray jsonSpotsDet = Json.parse( jsonString ).asObject().get( "spots" ).asArray();
			final Model model = new Model();
			final Spot vertexRef = model.getGraph().vertexRef();
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			int maxT = 0;
			model.getGraph().getLock().writeLock().lock();
			try
			{
				for ( final JsonValue jsonValue : jsonSpotsDet )
				{
					final JsonArray posArray = jsonValue.asObject().get( "pos" ).asArray();
					final JsonArray covArray = jsonValue.asObject().get( "covariance" ).asArray();
					for ( int i = 0; i < 3; i++ )
					{
						pos[ i ] = posArray.get( i ).asDouble();
						for ( int j = 0; j < 3; j++ )
						{
							cov[ i ][ j ] = covArray.get( i * 3 + j ).asDouble();
						}
					}
					final int t = jsonValue.asObject().get( "t" ).asInt();
					model.getGraph().addVertex( vertexRef ).init( t, pos, cov );
					maxT = Math.max( maxT, t );
				}
			}
			finally
			{
				model.getGraph().getLock().writeLock().unlock();
			}
			System.out.println( model.getGraph().vertices().size() + " spots were detected in total" );

			// Linking
			final Iterator< Integer > timepointIterator = IntStream.rangeClosed( 1, maxT )
					.boxed().sorted( Collections.reverseOrder() ).iterator();
			int totalEdges = 0;
			while ( timepointIterator.hasNext() )
			{
				final int timepoint = timepointIterator.next();
				final JsonArray jsonSpotsTraIn = Json.array();
				final Predicate< Spot > spotFilter = spot -> spot.getTimepoint() == timepoint;
				addSpotsToJsonFlow( model, model.getGraph().vertices(), jsonSpotsTraIn, spotFilter );
				final JsonObject jsonTra = Json.object()
						.add( "t", timepoint )
						.add( "spots", jsonSpotsTraIn );
				try (PrintWriter out = new PrintWriter( spotsFileName ))
				{
					out.println( jsonTra.toString() );
				}
				if ( config.get( "use_opticalflow" ).asBoolean() )
				{
					pb.command(
							"./run_elephant.sh", "linking",
							args[ 0 ],
							args[ 2 ],
							spotsFileName ).start().waitFor();
				}
				System.out.println( "run_linking completed" );
				final JsonArray jsonSpotsTraOut = Json.parse( readString( spotsFileName ) ).asObject().get( "spots" ).asArray();
				linkSpots( model, jsonSpotsTraOut, timepoint, timepointIterator, pos, cov, config );
				System.out.println( ( model.getGraph().edges().size() - totalEdges ) + " edges were detected at timepoint " + timepoint );
				totalEdges = model.getGraph().edges().size();
			}

			// Export
			id = MINIMUM_ID;
			final double[] cov1d = new double[ 9 ];
			final List< CTCTrackEntity > trackList = new ArrayList<>();
			final JsonArray jsonSpotsExport = Json.array();
			model.getGraph().getLock().readLock().lock();
			try
			{
				final PoolCollectionWrapper< Spot > spots = model.getGraph().vertices();
				final RefList< Spot > rootSpots = RefCollections.createRefList( spots );
				for ( final Spot spot : spots )
				{
					if ( spot.incomingEdges().isEmpty() )
						rootSpots.add( spot );
				}
				final Spot ref = model.getGraph().vertexRef();
				for ( int i = 0; i < rootSpots.size(); i++ )
				{
					rootSpots.get( i, ref );
					buildResult( model, ref, trackList, UNSET, 0, jsonSpotsExport, pos, cov, cov1d );
				}
				model.getGraph().releaseRef( ref );
			}
			finally
			{
				model.getGraph().getLock().readLock().unlock();
			}
			try (PrintWriter out = new PrintWriter( spotsFileName ))
			{
				out.println( jsonSpotsExport.toString() );
			}
			try
			{
				Files.createDirectories( Paths.get( args[ 1 ] ) );
			}
			catch ( final IOException e )
			{
				e.printStackTrace();
			}
			final File file = Paths.get( args[ 1 ], RES_FILENAME ).toFile();
			try (final FileWriter fileWriter = new FileWriter( file ))
			{
				final CSVWriter writer = new CSVWriter( fileWriter, ' ', CSVWriter.NO_QUOTE_CHARACTER );
				try
				{
					for ( int i = 0; i < trackList.size(); i++ )
						writer.writeNext( trackList.get( i ).toCsvEntry() );
				}
				finally
				{
					writer.close();
				}
			}
			catch ( final IOException e )
			{
				e.printStackTrace();
			}
			pb.command(
					"./run_elephant.sh", "export",
					args[ 0 ],
					args[ 2 ],
					spotsFileName,
					"--output", args[ 1 ] ).start().waitFor();
			System.out.println( "run_export completed" );
			Files.deleteIfExists( Paths.get( spotsFileName ) );
		}
		catch ( final IOException e )
		{
			e.printStackTrace();
		}
		catch ( final InterruptedException e )
		{
			e.printStackTrace();
		}

	}

	private static String readString( final String path ) throws IOException
	{
		String content = null;
		try (Stream< String > lines = Files.lines( Paths.get( path ) ))
		{
			content = lines.collect( Collectors.joining( System.lineSeparator() ) );
		}
		catch ( final IOException e )
		{
			throw e;
		}
		return content;
	}

	private static void addSpotsToJsonFlow( final Model model, final Collection< Spot > spots, final JsonArray jsonSpots, final Predicate< Spot > filter )
	{
		final double[] pos = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		final double[] cov1d = new double[ 9 ];
		model.getGraph().getLock().readLock().lock();
		try
		{
			for ( final Spot spot : spots )
			{
				if ( filter == null || filter.test( spot ) )
				{
					spot.localize( pos );
					spot.getCovariance( cov );
					for ( int i = 0; i < 3; i++ )
						for ( int j = 0; j < 3; j++ )
							cov1d[ i * 3 + j ] = cov[ i ][ j ];
					final int id = spot.getInternalPoolIndex();

					final JsonObject jsonSpot = Json.object()
							.add( "pos", Json.array( pos ) )
							.add( "covariance", Json.array( cov1d ) )
							.add( "id", id );
					jsonSpots.add( jsonSpot );
				}
			}
		}
		finally
		{
			model.getGraph().getLock().readLock().unlock();
		}
	}

	private static void linkSpots( final Model model, final JsonArray jsonSpots, final int timepoint, final Iterator< Integer > timepointIterator, final double[] pos, final double[][] cov, final JsonObject config )
	{

		final List< Integer > linkedSpotIds = new ArrayList<>();
		final Map< Integer, Double > distMap = new HashMap<>();
		final Map< Integer, Double > sqDispMap = new HashMap<>();
		final Set< Integer > interpolatedIdSet = new HashSet<>();

		// load settings from config
		final boolean useOpticalFlow = config.get( "use_opticalflow" ).asBoolean();
		final boolean useInterpolation = config.get( "use_interpolation" ).asBoolean();
		final double linkingThreshold = config.get( "linking_threshold" ).asDouble();
		final double squaredDistanceThreshold = linkingThreshold * linkingThreshold;
		final int searchDepth = config.get( "search_depth" ).asInt();
		final int searchNeighbors = config.get( "search_neighbors" ).asInt();
		final int maxEdges = config.get( "max_edges" ).asInt();
		final double divisionMinDisplacement = config.get( "division_min_displacement" ).asDouble();
		final double divisionAcceptableDistance = config.get( "division_acceptable_distance" ).asDouble();

		final Spot sourceRef = model.getGraph().vertexRef();
		final Spot targetRef = model.getGraph().vertexRef();
		final Spot newSpotRef = model.getGraph().vertexRef();
		final Link edgeRef = model.getGraph().edgeRef();

		final Comparator< Link > comparatorLink = new Comparator< Link >()
		{
			@Override
			public int compare( Link o1, Link o2 )
			{
				return Double.compare(
						distMap.getOrDefault( o1.getInternalPoolIndex(), squaredDistanceOf( model.getGraph(), o1 ) ),
						distMap.getOrDefault( o2.getInternalPoolIndex(), squaredDistanceOf( model.getGraph(), o2 ) ) );
			}

		};

		model.getGraph().getLock().readLock().lock();
		try
		{
			final RefList< Link > linksToRemove = RefCollections.createRefList( model.getGraph().edges() );
			final Supplier< Stream< Spot > > spotSupplier = () -> model.getGraph().vertices().stream().filter( s -> s.getTimepoint() == timepoint );
			for ( int n = 0; n < 5; n++ )
			{
				for ( final JsonValue jsonValue : jsonSpots )
				{
					final JsonObject jsonSpot = jsonValue.asObject();
					final int spotId = jsonSpot.get( "id" ).asInt();
					if ( linkedSpotIds.contains( spotId ) )
						continue;
					final Spot spot = spotSupplier.get().filter( s -> s.getInternalPoolIndex() == spotId ).findFirst().orElse( null );
					if ( spot == null )
					{
						System.out.println( "spot " + spot + " was not found" );
					}
					else
					{
						final JsonArray jsonPositions = jsonSpot.get( "pos" ).asArray();
						double sqDisp = useOpticalFlow ? jsonSpot.get( "sqdisp" ).asDouble() : 0;
						for ( int t = 0; t < searchDepth && 0 <= ( timepoint - 1 - t ); t++ )
						{
							final int timepointToSearch = timepoint - 1 - t;
							final SpatialIndex< Spot > spatialIndex = model.getSpatioTemporalIndex().getSpatialIndex( timepointToSearch );
							final IncrementalNearestNeighborSearch< Spot > inns = spatialIndex.getIncrementalNearestNeighborSearch();
							for ( int j = 0; j < 3; j++ )
								pos[ j ] = jsonPositions.get( j ).asDouble();
							inns.search( new RealPoint( pos ) );
							for ( int i = 0; inns.hasNext() && i < searchNeighbors; i++ )
							{
								final Spot nearestSpot = inns.next();
								final double squaredDistance = inns.getSquareDistance();
								if ( squaredDistanceThreshold < squaredDistance )
									break;
								// TODO: Division detector
								if ( !useOpticalFlow )
									sqDisp = squaredDistance;
								int acceptableEdges = divisionMinDisplacement < sqDisp ? maxEdges : 1;
								for ( final Link edge : nearestSpot.outgoingEdges() )
								{
									if ( divisionMinDisplacement < sqDispMap.getOrDefault( edge.getInternalPoolIndex(), 0.0 ) )
										acceptableEdges = maxEdges;
									if ( ( squaredDistance < divisionAcceptableDistance ) && ( distMap.getOrDefault( edge.getInternalPoolIndex(), 0.0 ) < divisionAcceptableDistance ) )
										acceptableEdges = maxEdges;
								}
								final Supplier< Stream< Link > > edgeSupplier = () -> StreamSupport.stream( nearestSpot.outgoingEdges().spliterator(), false );
								if ( acceptableEdges <= edgeSupplier.get().count() )
								{
									final Link longestEdge = edgeSupplier.get().max( comparatorLink ).orElse( null );
									if ( longestEdge != null )
									{
										if ( distMap.getOrDefault( longestEdge.getInternalPoolIndex(), squaredDistanceOf( model.getGraph(), longestEdge ) ) < squaredDistance )
											continue;
										else
										{
											edgeRef.refTo( longestEdge );
											edgeRef.getSource( sourceRef );
											edgeRef.getTarget( targetRef );
											if ( 0 < sourceRef.getInternalPoolIndex() && 0 < targetRef.getInternalPoolIndex() )
											{
												linksToRemove.add( edgeRef );
												linkedSpotIds.remove( ( Integer ) targetRef.getInternalPoolIndex() );
											}
										}
									}
								}
								if ( useInterpolation && ( 0 < t ) && !interpolatedIdSet.contains( spotId ) )
								{
									spot.getCovariance( cov );
									model.getGraph().getLock().readLock().unlock();
									model.getGraph().getLock().writeLock().lock();
									try
									{
										model.getGraph().addVertex( newSpotRef ).init( timepoint - 1, pos, cov );
										nearestSpot.refTo( newSpotRef );
										interpolatedIdSet.add( spot.getInternalPoolIndex() );
										model.getGraph().getLock().readLock().lock();
									}
									finally
									{
										model.getGraph().getLock().writeLock().unlock();
									}
								}
								model.getGraph().getLock().readLock().unlock();
								model.getGraph().getLock().writeLock().lock();
								try
								{
									final Link edge = model.getGraph().addEdge( nearestSpot, spot ).init();
									linkedSpotIds.add( spot.getInternalPoolIndex() );
									distMap.put( edge.getInternalPoolIndex(), squaredDistance );
									sqDispMap.put( edge.getInternalPoolIndex(), sqDisp );
									model.getGraph().getLock().readLock().lock();
								}
								finally
								{
									model.getGraph().getLock().writeLock().unlock();
								}
								break;
							}
							if ( linkedSpotIds.contains( spotId ) )
								break;
						}
					}
				}
			}
			model.getGraph().getLock().readLock().unlock();
			model.getGraph().getLock().writeLock().lock();
			try
			{
				for ( final Link edge : linksToRemove )
				{
					edge.getSource( sourceRef );
					edge.getTarget( targetRef );
					if ( 0 < sourceRef.getInternalPoolIndex() && 0 < targetRef.getInternalPoolIndex() )
						model.getGraph().remove( edge );
				}
				model.getGraph().getLock().readLock().lock();
			}
			finally
			{
				model.getGraph().getLock().writeLock().unlock();
			}
			model.getGraph().releaseRef( sourceRef );
			model.getGraph().releaseRef( targetRef );
			model.getGraph().releaseRef( newSpotRef );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}
		finally
		{
			model.getGraph().getLock().readLock().unlock();
		}
	}

	private static double squaredDistanceOf( final ModelGraph graph, final Link edge )
	{
		double squaredDistance = 0;
		final Spot ref = graph.vertexRef();
		try
		{
			final double[] posSource = new double[ 3 ];
			edge.getSource( ref );
			ref.localize( posSource );
			final double[] posTarget = new double[ 3 ];
			edge.getTarget( ref );
			ref.localize( posTarget );
			for ( int i = 0; i < 3; i++ )
			{
				final double diff = posSource[ i ] - posTarget[ i ];
				squaredDistance += diff * diff;
			}
		}
		finally
		{
			graph.releaseRef( ref );
		}
		return squaredDistance;
	}

	private static class CTCTrackEntity
	{
		private final int id;

		private final int start;

		private final int end;

		private final int parent;

		public CTCTrackEntity( final int id, final int start, final int end, final int parent )
		{
			this.id = id;
			this.start = start;
			this.end = end;
			this.parent = parent;
		}

		public String[] toCsvEntry()
		{
			return new String[] {
					Integer.toString( id ),
					Integer.toString( start ),
					Integer.toString( end ),
					Integer.toString( parent )
			};
		}
	}

	private static void buildResult( final Model model, final Spot spot, final List< CTCTrackEntity > trackList, int start, int parent, final JsonArray jsonSpots,
			final double[] pos, final double[][] cov, final double[] cov1d )
	{
		final RefList< Link > outgoingEdges = RefCollections.createRefList( model.getGraph().edges() );
		for ( final Link edge : spot.outgoingEdges() )
			outgoingEdges.add( edge );
		if ( start == UNSET )
			start = spot.getTimepoint();
		// add json entry
		spot.localize( pos );
		spot.getCovariance( cov );
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
				cov1d[ i * 3 + j ] = cov[ i ][ j ];
		jsonSpots.add( Json.object()
				.add( "t", spot.getTimepoint() )
				.add( "pos", Json.array( pos ) )
				.add( "covariance", Json.array( cov1d ) )
				.add( "value", id ) );
		if ( outgoingEdges.size() == 0 )
		{
			trackList.add( new CTCTrackEntity( id, start, spot.getTimepoint(), parent ) );
			id++;
			return;
		}
		if ( 1 < outgoingEdges.size() )
		{
			trackList.add( new CTCTrackEntity( id, start, spot.getTimepoint(), parent ) );
			parent = id;
			id++;
			start = UNSET;
		}
		for ( final Link edge : outgoingEdges )
			buildResult( model, edge.getTarget( spot ), trackList, start, parent, jsonSpots, pos, cov, cov1d );
	}

}
