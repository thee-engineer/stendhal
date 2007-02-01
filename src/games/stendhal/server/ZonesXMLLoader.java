/*
 * @(#) src/games/stendhal/server/ZonesXMLLoader.java
 *
 * $Id$
 */
package games.stendhal.server;

//
//

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.apache.log4j.Logger;

import marauroa.common.Log4J;
import marauroa.server.game.RPWorld;
import games.stendhal.common.ConfigurableFactory;
import games.stendhal.common.ConfigurableFactoryHelper;
import games.stendhal.common.ConfigurableFactoryContextImpl;
import games.stendhal.server.entity.portal.Portal;
import games.stendhal.server.maps.IContent;
import games.stendhal.server.maps.ZoneConfigurator;

/**
 * Load and configure zones via an XML configuration file.
 */
public class ZonesXMLLoader extends DefaultHandler {
	protected static final int SCOPE_NONE		= 0;
	protected static final int SCOPE_CONFIGURATOR	= 1;
	protected static final int SCOPE_PORTAL		= 2;

	private static final Logger logger = Log4J.getLogger(ZonesXMLLoader.class);

	/**
	 * The zone [data] loader.
	 */
	protected ZoneXMLLoader		zoneLoader;

	/**
	 * A list of zone descriptors.
	 */
	protected LinkedList<ZoneDesc>	zoneDescriptors;

	/**
	 * The current zone descriptor.
	 */
	protected ZoneDesc		zdesc;

	/**
	 * The current configurator descriptor.
	 */
	protected ConfiguratorDesc	cdesc;

	/**
	 * The current portal descriptor.
	 */
	protected PortalDesc		pdesc;

	/**
	 * The current attribute name.
	 */
	String				attrName;

	/**
	 * The current text content.
	 */
	StringBuffer			content;

	/**
	 * The current [meaningful] xml tree scope.
	 */
	int				scope;


	/**
	 * Create an xml based loader of zones.
	 */
	public ZonesXMLLoader() {
		zoneLoader = ZoneXMLLoader.get();
		content = new StringBuffer();
	}


	//
	// ZonesXMLLoader
	//

	/**
	 * Load zones into a world using a config file.
	 *
	 * @param	world		The world to load into.
	 * @param	ref		The name of the config resource file.
	 *
	 * @return	A list of loaded zones.
	 *
	 * @throws	SAXException	If a SAX error occured.
	 * @throws	IOException	If an I/O error occured.
	 * @throws	FileNotFoundException
	 *				If the resource was not found.
	 */
	public Collection<StendhalRPZone> load(RPWorld world, String ref)
	 throws SAXException, IOException {
		InputStream in = getClass().getClassLoader().getResourceAsStream(ref);

		if(in == null) {
			throw new FileNotFoundException(
				"Cannot find resource: " + ref);
		}

		try {
			return load(world, in);
		} finally {
			in.close();
		}
	}


	/**
	 * Load zones into a world using a config file.
	 *
	 * @param	world		The world to load into.
	 * @param	in		The config file stream.
	 *
	 * @return	A list of loaded zones.
	 *
	 * @throws	SAXException	If a SAX error occured.
	 * @throws	IOException	If an I/O error occured.
	 */
	public Collection<StendhalRPZone> load(RPWorld world, InputStream in)
	 throws SAXException, IOException {
		SAXParser	saxParser;
		HashMap<String, StendhalRPZone>	zones;


		/*
		 * Use the default (non-validating) parser
		 */
		SAXParserFactory factory = SAXParserFactory.newInstance();

		try {
			saxParser = factory.newSAXParser();
		} catch(ParserConfigurationException ex) {
			throw new SAXException(ex);
		}


		/*
		 * Parse the XML
		 */
		zoneDescriptors = new LinkedList<ZoneDesc>();
		zdesc = null;
		cdesc = null;
		pdesc = null;
		attrName = null;
		scope = SCOPE_NONE;

		saxParser.parse(in, this);


		/*
		 * Load each zone
		 */
		zones = new HashMap<String, StendhalRPZone>();

		for(ZoneDesc zdesc : zoneDescriptors) {
			String name = zdesc.getName();

			logger.info("Loading zone: " + name);

			try {
				zones.put(name, load(world, zdesc));
			} catch(Exception ex) {
				logger.error(
					"Error loading zone: " + name, ex);
			}
		}


		/*
		 * Configure each [loaded] zone
		 */
		for(ZoneDesc zdesc : zoneDescriptors) {
			StendhalRPZone	zone;

			if((zone = zones.get(zdesc.getName())) != null) {
				/*
				 * Portals
				 */
				Iterator<PortalDesc> piter =
					zdesc.getPortals();

				while(piter.hasNext()) {
					configurePortal(zone, piter.next());
				}

				/*
				 * Custom configurators
				 */
				Iterator<ConfiguratorDesc> citer =
					zdesc.getConfigurators();

				while(citer.hasNext()) {
					configureZone(zone, citer.next());
				}
			}
		}


		return zones.values();
	}


	/**
	 * Configure a zone.
	 *
	 *
	 */
	protected void configureZone(StendhalRPZone zone,
	 ConfiguratorDesc cdesc) {
		String	className;
		Class	clazz;
		Object	obj;


		className = cdesc.getClassName();


		/*
		 * Load class
		 */
		try {
			clazz = Class.forName(className);
		} catch(ClassNotFoundException ex) {
			logger.error(
				"Unable to find zone configurator: "
					+ className);

			return;
		}


		/*
		 * Create instance
		 */
		try {
			obj = clazz.newInstance();
		} catch(InstantiationException ex) {
			logger.error(
				"Error creating zone configurator: "
					+ className,
				ex);

			return;
		} catch(IllegalAccessException ex) {
			logger.error(
				"Error accessing zone configurator: "
					+ className,
				ex);

			return;
		}


		/*
		 * Apply class
		 */
		if(obj instanceof ZoneConfigurator) {
			logger.info("Configuring zone [" + zone.getID().getID()
				+ "] using ZoneConfigurator with: "
				+ className);

			((ZoneConfigurator) obj).configureZone(
				zone, cdesc.getAttributes());
		} else if(obj instanceof IContent) {
			logger.info("Configuring zone [" + zone.getID().getID()
				+ "] using IContent with: "
				+ className);

			((IContent) obj).build();
		} else {
			logger.warn(
				"Unsupported zone configurator: "
					+ className);
		}
	}


	/**
	 * Configure a portal.
	 *
	 *
	 */
	protected void configurePortal(StendhalRPZone zone, PortalDesc pdesc) {
		String			className;
		ConfigurableFactory	factory;
		Portal			portal;
		Object			reference;


		if((className = pdesc.getClassName()) == null) {
			/*
			 * Default implementation
			 */
			className = Portal.class.getName();
		}

		try
		{
			if((factory = ConfigurableFactoryHelper.getFactory(
			 className)) == null) {
				logger.warn("Unable to get portal factory: "
					+ className);

				return;
			}

			portal = (Portal) factory.create(
				new ConfigurableFactoryContextImpl(
					pdesc.getAttributes()));

			zone.assignRPObjectID(portal);

			portal.set(pdesc.getX(), pdesc.getY());
			portal.setReference(pdesc.getReference());

			if((reference = pdesc.getDestinationReference())
			 != null) {
				portal.setDestination(
					pdesc.getDestinationZone(), reference);
			}

			zone.addPortal(portal);
		} catch(IllegalArgumentException ex) {
			logger.error("Error with portal factory", ex);
		}
	}


	/**
	 * Load zone data and create a zone from it.
	 * Most of this should be moved directly into ZoneXMLLoader.
	 *
	 *
	 */
	protected StendhalRPZone load(RPWorld world, ZoneDesc desc)
	 throws SAXException, IOException {
		String	name;

		name = desc.getName();

		StendhalRPZone zone = new StendhalRPZone(name);

		ZoneXMLLoader.XMLZone zonedata =
			zoneLoader.load("data/maps/" + desc.getFile());

		zone.addLayer(
			name + "_0_floor", zonedata.getLayer("0_floor"));

		zone.addLayer(
			name + "_1_terrain", zonedata.getLayer("1_terrain"));

		zone.addLayer(
			name + "_2_object", zonedata.getLayer("2_object"));

		zone.addLayer(
			name + "_3_roof", zonedata.getLayer("3_roof"));

		byte[] layer = zonedata.getLayer("4_roof_add");

		if (layer != null) {
			zone.addLayer(name + "_4_roof_add", layer);
		}

		zone.addCollisionLayer(
			name + "_collision", zonedata.getLayer("collision"));

		zone.addProtectionLayer(
			name + "_protection", zonedata.getLayer("protection"));

		if (zonedata.isInterior()) {
			zone.setPosition();
		} else {
			zone.setPosition(
				zonedata.getLevel(),
				zonedata.getX(),
				zonedata.getY());
		}

		world.addRPZone(zone);

		zone.populate(zonedata.getLayer("objects"));

		return zone;
	}


	//
	// ContentHandler
	//

	@Override
	public void startElement(String namespaceURI, String lName,
	 String qName, Attributes attrs) {
		String	s;
		int	x;
		int	y;
		String	zone;
		Object	reference;


		if(qName.equals("zone")) {
			if((s = attrs.getValue("name")) == null) {
				logger.warn("Unnamed zone");
			} else {
				zdesc = new ZoneDesc(
					s, attrs.getValue("file"));
			}
		} else if(qName.equals("title")) {
			content.setLength(0);
		} else if(qName.equals("configurator")) {
			if((s = attrs.getValue("class-name")) == null) {
				logger.warn("Configurator without class-name");
			} else {
				cdesc = new ConfiguratorDesc(s);
				scope = SCOPE_CONFIGURATOR;
			}
		} else if(qName.equals("portal")) {
			if((s = attrs.getValue("x")) == null) {
				logger.warn("Portal without 'x' coordinate");
				return;
			}

			try {
				x = Integer.parseInt(s);
			} catch(NumberFormatException ex) {
				logger.warn("Invalid portal 'x' coordinate: "
					+ s);
				return;
			}

			if((s = attrs.getValue("y")) == null) {
				logger.warn("Portal without 'y' coordinate");
				return;
			}

			try {
				y = Integer.parseInt(s);
			} catch(NumberFormatException ex) {
				logger.warn("Invalid portal 'y' coordinate: "
					+ s);
				return;
			}

			if((s = attrs.getValue("ref")) == null) {
				logger.warn("Portal without 'ref' value");
				return;
			}

			/*
			 * For now, treat valid number strings as Integer refs
			 */
			try {
				reference = new Integer(s);
			} catch(NumberFormatException ex) {
				reference = s;
			}

			pdesc = new PortalDesc(x, y, reference);
			scope = SCOPE_PORTAL;
		} else if(qName.equals("attribute")) {
			if((attrName = attrs.getValue("name")) == null) {
				logger.warn("Unnamed attribute");
			} else {
				content.setLength(0);
			}
		} else if(qName.equals("implementation")) {
			if((s = attrs.getValue("class-name")) == null) {
				logger.warn("Implmentation without class-name");
			} else if(pdesc != null) {
				pdesc.setImplementation(s);
			}
		} else if(qName.equals("destination")) {
			if((zone = attrs.getValue("zone")) == null) {
				logger.warn("Portal destination without zone");
				return;
			}

			if((s = attrs.getValue("ref")) == null) {
				logger.warn("Portal dest without 'ref' value");
				return;
			}

			/*
			 * For now, treat valid number strings as Integer refs
			 */
			try {
				reference = new Integer(s);
			} catch(NumberFormatException ex) {
				reference = s;
			}

			if((scope == SCOPE_PORTAL) && (pdesc != null))
				pdesc.setDestination(zone, reference);
		} else {
			logger.warn("Unknown XML element: " + qName);
		}
	}


	@Override
	public void endElement(String namespaceURI, String sName,
	 String qName) {
		if(qName.equals("zone")) {
			if(zdesc != null) {
				zoneDescriptors.add(zdesc);
				zdesc = null;
			}
		} else if(qName.equals("title")) {
			if(zdesc != null) {
				String s = content.toString().trim();

				if(s.length() != 0) {
					zdesc.setTitle(s);
				}
			}
		} else if(qName.equals("configurator")) {
			if(zdesc != null) {
				if(cdesc != null) {
					zdesc.addConfigurator(cdesc);
				}

				cdesc = null;
			}

			scope = SCOPE_NONE;
		} else if(qName.equals("portal")) {
			if(zdesc != null) {
				if(pdesc != null) {
					zdesc.addPortal(pdesc);
				}

				pdesc = null;
			}

			scope = SCOPE_NONE;
		} else if(qName.equals("attribute")) {
			if(attrName != null) {
				if((scope == SCOPE_CONFIGURATOR)
				 && (cdesc != null)) {
					cdesc.setAttribute(
						attrName,
						content.toString().trim());
				}
				else if((scope == SCOPE_PORTAL)
				 && (pdesc != null)) {
					pdesc.setAttribute(
						attrName,
						content.toString().trim());
				}
			}
		}
	}


	@Override
	public void characters(char buf[], int offset, int len) {
		content.append(buf, offset, len);
	}

	//
	//

	/*
	 * XXX - THIS REQUIRES StendhalRPWorld SETUP (i.e. marauroa.ini)
	 */
	public static void main(String [] args) throws Exception {
		if(args.length != 1) {
			System.err.println(
				"Usage: java "
					+ ZonesXMLLoader.class.getName()
						+ " <filename>");
			System.exit(1);
		}


		ZonesXMLLoader loader = new ZonesXMLLoader();

		try {
			loader.load(
				StendhalRPWorld.get(),
				new java.io.FileInputStream(args[0]));
		} catch(org.xml.sax.SAXParseException ex) {
			System.err.print(
				"Source "
				+ args[0]
				+ ":" + ex.getLineNumber()
				+ "<" + ex.getColumnNumber() + ">");

			throw ex;
		}
	}

	//
	//

	/**
	 * A zone descriptor.
	 */
	protected static class ZoneDesc {
		protected String	name;
		protected String	file;
		protected String	title;
		protected ArrayList<ConfiguratorDesc>	configurators;
		protected ArrayList<PortalDesc>		portals;


		public ZoneDesc(String name, String file) {
			this.name = name;

			/*
			 * XXX - Temp compatibility (map name to filename)
			 */
			file = name.replace("-", "sub_") + ".xstend";

			this.file = file;

			configurators = new ArrayList<ConfiguratorDesc>();
			portals = new ArrayList<PortalDesc>();
		}


		//
		//
		//

		/**
		 * Add a configurator descriptor.
		 *
		 */
		public void addConfigurator(ConfiguratorDesc configurator) {
			configurators.add(configurator);
		}


		/**
		 * Add a portal descriptor.
		 *
		 */
		public void addPortal(PortalDesc portal) {
			portals.add(portal);
		}


		/**
		 * Get an iterator of configurator descriptors.
		 *
		 */
		public Iterator<ConfiguratorDesc> getConfigurators() {
			return configurators.iterator();
		}


		/**
		 * Get the zone file.
		 *
		 */
		public String getFile() {
			return file;
		}


		/**
		 * Get the zone name.
		 *
		 */
		public String getName() {
			return name;
		}


		/**
		 * Get an iterator of portal descriptors.
		 *
		 */
		public Iterator<PortalDesc> getPortals() {
			return portals.iterator();
		}


		/**
		 * Get the zone title.
		 *
		 */
		public String getTitle() {
			return title;
		}


		/**
		 * Set the zone title.
		 *
		 */
		public void setTitle(String title) {
			this.title = title;
		}
	}


	/**
	 * A zone configurator descriptor.
	 */
	protected static class ConfiguratorDesc {
		protected String	className;
		protected HashMap<String, String>	attributes;


		public ConfiguratorDesc(String className) {
			this.className = className;

			attributes = new HashMap<String, String>();
		}

		//
		//
		//

		/**
		 * Get the attributes.
		 *
		 */
		public Map<String, String> getAttributes() {
			return attributes;
		}


		/**
		 * Get the class name.
		 *
		 */
		public String getClassName() {
			return className;
		}


		/**
		 * Set an attribute.
		 *
		 */
		public void setAttribute(String name, String value) {
			attributes.put(name, value);
		}
	}


	/**
	 * A portal descriptor.
	 */
	protected static class PortalDesc {
		protected int		x;
		protected int		y;
		protected Object	reference;
		protected String	destinationZone;
		protected Object	destinationReference;
		protected String	className;
		protected HashMap<String, String>	attributes;


		public PortalDesc(int x, int y, Object reference) {
			this.x = x;
			this.y = y;
			this.reference = reference;

			destinationZone = null;
			destinationReference = null;

			className = null;
			attributes = new HashMap<String, String>();
		}

		//
		//
		//

		/**
		 * Get the attributes.
		 *
		 */
		public Map<String, String> getAttributes() {
			return attributes;
		}


		/**
		 * Get the implementation class name.
		 *
		 */
		public String getClassName() {
			return className;
		}


		/**
		 * Get the destination reference.
		 *
		 */
		public Object getDestinationReference() {
			return destinationReference;
		}


		/**
		 * Get the destination zone.
		 *
		 */
		public String getDestinationZone() {
			return destinationZone;
		}


		/**
		 * Get the implementation class name.
		 *
		 */
		public String getImplementation() {
			return className;
		}


		/**
		 * Get the reference.
		 *
		 */
		public Object getReference() {
			return reference;
		}


		/**
		 * Get the X coordinate.
		 *
		 */
		public int getX() {
			return x;
		}


		/**
		 * Get the Y coordinate.
		 *
		 */
		public int getY() {
			return y;
		}


		/**
		 * Set an attribute.
		 *
		 */
		public void setAttribute(String name, String value) {
			attributes.put(name, value);
		}


		/**
		 * Set the destination zone/reference.
		 *
		 */
		public void setDestination(String zone, Object reference) {
			this.destinationZone = zone;
			this.destinationReference = reference;
		}


		/**
		 * Set the implementation class name.
		 *
		 */
		public void setImplementation(String className) {
			this.className = className;
		}
	}
}
