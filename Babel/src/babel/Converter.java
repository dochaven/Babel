package babel;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.json.JSONObject;

import javax.ws.rs.core.MediaType;
import java.net.URI;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: dochaven
 * Date: 4/30/13
 * Time: 12:13 AM
 * To change this template use File | Settings | File Templates.
 */
public class Converter {
    private static Converter _converter;
    private String SQLServerConnectionString;
    private String Neo4jConnectionString;

    /**
     * Singleton constructor
     */
    private Converter() {
        SQLServerConnectionString = "";
        Neo4jConnectionString = "";
    }

    /**
     * Singleton Accessor
     * @return the instance of this class
     */
    public static Converter GetInstance() {
        if (_converter == null)
            _converter = new Converter();
        return _converter;
    }

    private static String toJsonNameValuePairCollection(String name,
                                                        String value) {
        return String.format("{ \"%s\" : \"%s\" }", name, value);
    }

    /**
     * Configures Babel for connecting to the SQL Server.
     * @param sqlServerConnectionString SQL Server connection string
     */
    public void SetSQLServerConnectionString(String sqlServerConnectionString) {
        SQLServerConnectionString = sqlServerConnectionString;
    }

    /**
     * Configures Babel for connecting to the Neo4j Server.
     * @param neo4jConnectionString Neo4j connection string
     */
    public void SetNeo4jConnectionString(String neo4jConnectionString) {
        Neo4jConnectionString = neo4jConnectionString;
    }

    /**
     * Grabs the table info needed to properly convert the MS SQL database.
     * @return map of table info
     */
    public Map<String, Table> GrabSQLServerTableInfo() {
        String query = "SELECT INFO.TABLE_NAME, INFO.COLUMN_NAME, \n" +
                "       FK.FKTABLE_NAME, FK.FKCOLUMN_NAME \n" +
                "FROM\n" +
                "\n" +
                "(SELECT TABLE_NAME, COLUMN_NAME\n" +
                "        FROM INFORMATION_SCHEMA.COLUMNS) AS INFO\n" +
                "\n" +
                "LEFT OUTER JOIN\n" +
                "\n" +
                "(\n" +
                "  SELECT C.TABLE_NAME [TABLE_NAME], \n" +
                "         KCU.COLUMN_NAME [COLUMN_NAME],\n" +
                "         C2.TABLE_NAME [FKTABLE_NAME], \n" +
                "         KCU2.COLUMN_NAME [FKCOLUMN_NAME]\n" +
                "  FROM   INFORMATION_SCHEMA.TABLE_CONSTRAINTS C \n" +
                "         INNER JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU \n" +
                "           ON C.CONSTRAINT_SCHEMA = KCU.CONSTRAINT_SCHEMA \n" +
                "              AND C.CONSTRAINT_NAME = KCU.CONSTRAINT_NAME \n" +
                "         INNER JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS RC \n" +
                "           ON C.CONSTRAINT_SCHEMA = RC.CONSTRAINT_SCHEMA \n" +
                "              AND C.CONSTRAINT_NAME = RC.CONSTRAINT_NAME \n" +
                "         INNER JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS C2 \n" +
                "           ON RC.UNIQUE_CONSTRAINT_SCHEMA = C2.CONSTRAINT_SCHEMA \n" +
                "              AND RC.UNIQUE_CONSTRAINT_NAME = C2.CONSTRAINT_NAME \n" +
                "         INNER JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU2 \n" +
                "           ON C2.CONSTRAINT_SCHEMA = KCU2.CONSTRAINT_SCHEMA \n" +
                "              AND C2.CONSTRAINT_NAME = KCU2.CONSTRAINT_NAME \n" +
                "              AND KCU.ORDINAL_POSITION = KCU2.ORDINAL_POSITION \n" +
                "  WHERE  C.CONSTRAINT_TYPE = 'FOREIGN KEY'\n" +
                ") AS FK\n" +
                "\n" +
                "ON FK.TABLE_NAME = INFO.TABLE_NAME\n" +
                "   AND FK.COLUMN_NAME = INFO.COLUMN_NAME\n" +
                "ORDER BY INFO.TABLE_NAME, INFO.COLUMN_NAME";
        Map<String, Table> tables = new HashMap<String, Table>();

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(SQLServerConnectionString);
            Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(query);

            while (rs.next()) {
                String val = rs.getString("FKTABLE_NAME");
                boolean fk = rs.getString("FKTABLE_NAME") == null;
                Attribute Attr = new Attribute(rs.getString("COLUMN_NAME"),
                        fk ? Attribute.AttributeType.base : Attribute.AttributeType.foriegnkey);

                if (!fk) {
                    Attr.SetFKConstraint(rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME"));
                }

                if (!tables.containsKey(rs.getString("TABLE_NAME"))) {
                    Table newTable = new Table(rs.getString("TABLE_NAME"));
                    newTable.AddAttribute(Attr);
                    tables.put(rs.getString("TABLE_NAME"), newTable);
                } else {
                    Table table = tables.get(rs.getString("TABLE_NAME"));
                    table.AddAttribute(Attr);
                }
            }

            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        return GrabSQLServerIndexes(tables);
    }

    /**
     * Grabs the indexes for the tables.
     * @param tables map of table info
     * @return map of altered table info with indexes
     */
    public Map<String, Table> GrabSQLServerIndexes(Map<String, Table> tables) {
        String query = "SELECT tc.TABLE_NAME, COLUMN_NAME\n" +
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc\n" +
                "JOIN INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu \n" +
                "ON tc.CONSTRAINT_NAME = ccu.Constraint_name\n" +
                "WHERE tc.CONSTRAINT_TYPE = 'Primary Key'";

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(SQLServerConnectionString);
            Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(query);

            while (rs.next()) {
                if (!tables.containsKey(rs.getString("TABLE_NAME"))) {
                    //There is something wrong!
                } else {
                    tables.get(rs.getString("TABLE_NAME")).Keys.add(rs.getString("COLUMN_NAME"));
                }
            }

            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        return tables;
    }

    /**
     * Inserts the data for a given base table into the Neo4j database.
     * @param table table to insert
     */
    public void ConvertBaseTable(Table table) {
        System.out.println("Starting to convert: " + table.TableName);
        String query = "SELECT *\n" +
                "FROM [" + table.TableName + "];";
        Map<String, String> Vals = new HashMap<String, String>();

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(SQLServerConnectionString);
            Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(query);

            while (rs.next()) {
                for (Attribute a : table.Attr) {
                    Vals.put(a.Name, rs.getString(a.Name));
                }
                AddNode(Vals, table.Attr, table.TableName);
                Vals = new HashMap<String, String>();
            }

            //Add Index:
            AddIndex(table);

            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        System.out.println("Finished converting: " + table.TableName);
    }

    //Unfortunately I don't know how to add labels in batch node insertion, so don't use this:
    public void ConvertBaseTableBatch(Table table) {
        System.out.println("Starting to convert: " + table.TableName);
        String query = "SELECT *\n" +
                "FROM [" + table.TableName + "];";
        Map<String, String> Vals = new HashMap<String, String>();

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(SQLServerConnectionString);
            Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(query);

            final String nodeEntryPointUri = Neo4jConnectionString + "batch";

            WebResource resource = Client.create()
                    .resource(nodeEntryPointUri);

            StringBuilder sb = new StringBuilder();

            sb.append("[");

            while (rs.next()) {
                for (Attribute a : table.Attr) {
                    Vals.put(a.Name, rs.getString(a.Name));
                }
                //AddNode(Vals, table.Attr, table.TableName);

                sb.append(" { \"method\" : \"POST\", \"to\" : \"/node\", \"body\" : \n{ ");

                for (int i = 0; i < table.Attr.size(); i++){
                    sb.append("\"" + table.Attr.get(i).Name + "\" : \"" + Vals.get(table.Attr.get(i).Name) + "\"");
                    if(i+1 < table.Attr.size()) sb.append(", ");
                }

                sb.append("}, \"label\" : \"" + table.TableName.replace(' ', '_') + "\"");

                sb.append("},\n");

                Vals = new HashMap<String, String>();
            }

            sb.deleteCharAt(sb.lastIndexOf(","));
            sb.append(" ]");

            ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(sb.toString())
                    .post(ClientResponse.class);

            response.close();

            //Add Index:
            AddIndex(table);

            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        System.out.println("Finished converting: " + table.TableName);
    }

    /**
     * Inserts the data for a given arrow table into the Neo4j database.
     * @param table table to insert
     */
    public void ConvertArrowTable(Table table){
        System.out.println("Starting to convert: " + table.TableName);
        String query = "SELECT *\n" +
                "FROM [" + table.TableName + "];";
        Map<String, String> Vals = new HashMap<String, String>();

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(SQLServerConnectionString);
            Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(query);

            while (rs.next()) {
                URI node = null;
                for (Attribute a : table.Attr) {
                    if (table.Keys.contains(a.Name)) {
                        final String nodeQuery = Neo4jConnectionString
                                + "label/" + a.fkTable.replace(' ', '_') + "/nodes?"
                                + a.fkAttribute + "=%22"
                                + rs.getString(a.Name).replace(' ', '+')
                                + "%22";

                        WebResource resource = Client.create()
                                .resource(nodeQuery);

                        ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                                .get(ClientResponse.class);

                        String entity = response.getEntity(String.class);
                        entity = entity.substring(1);
                        entity = entity.substring(0, entity.length() - 2);

                        JSONObject jsonData = new JSONObject(entity);
                        String value = (String) jsonData.get("self");

                        node = URI.create(value);
                    }
                    Vals.put(a.Name, rs.getString(a.Name));
                }
                URI newNode = AddNode(Vals, table.Attr, table.TableName);

                if(node == null) throw new NullPointerException("reference node was not found");

                AddRelationship(Vals, table.Attr, table, node, newNode);
                Vals = new HashMap<String, String>();
            }
            AddIndex(table);

            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        System.out.println("Finished converting: " + table.TableName);
    }

    /**
     * Inserts the data for a given relationship table into the Neo4j database.
     * @param table table to insert
     */
    public void ConvertRelationshipTable(Table table) {
        System.out.println("Starting to convert: " + table.TableName);
        String query = "SELECT *\n" +
                "FROM [" + table.TableName + "];";
        Map<String, String> Vals = new HashMap<String, String>();

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(SQLServerConnectionString);
            Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(query);

            while (rs.next()) {
                int i = 0;
                URI node[] = new URI[2];
                for (Attribute a : table.Attr) {
                    if (table.Keys.contains(a.Name)) {
                        final String nodeQuery = Neo4jConnectionString
                                + "label/" + a.fkTable.replace(' ', '_') + "/nodes?"
                                + a.fkAttribute + "=%22"
                                + rs.getString(a.Name).replace(' ', '+')
                                + "%22";

                        WebResource resource = Client.create()
                                .resource(nodeQuery);

                        ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                                .get(ClientResponse.class);

                        String entity = response.getEntity(String.class);
                        entity = entity.substring(1);
                        entity = entity.substring(0, entity.length() - 2);

                        JSONObject jsonData = new JSONObject(entity);
                        String value = (String) jsonData.get("self");

                        node[i++] = URI.create(value);
                    }
                    Vals.put(a.Name, rs.getString(a.Name));
                }
                AddRelationship(Vals, table.Attr, table, node[1], node[0]);
                Vals = new HashMap<String, String>();
            }

            //We might want to add an index:
            //AddIndex(table);

            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        System.out.println("Finished converting: " + table.TableName);
    }

    /**
     * Inserts the data for a given spider table into the Neo4j database.
     * @param table table to insert
     */
    public void ConvertSpiderTable(Table table){
        System.out.println("Starting to convert: " + table.TableName);
        String query = "SELECT *\n" +
                "FROM [" + table.TableName + "];";
        Map<String, String> Vals = new HashMap<String, String>();

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(SQLServerConnectionString);
            Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(query);

            while (rs.next()) {
                ArrayList<URI> nodes = new ArrayList<URI>();
                for (Attribute a : table.Attr) {
                    if (table.Keys.contains(a.Name)) {
                        final String nodeQuery = Neo4jConnectionString
                                + "label/" + a.fkTable.replace(' ', '_') + "/nodes?"
                                + a.fkAttribute + "=%22"
                                + rs.getString(a.Name).replace(' ', '+')
                                + "%22";

                        WebResource resource = Client.create()
                                .resource(nodeQuery);

                        ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                                .get(ClientResponse.class);

                        String entity = response.getEntity(String.class);
                        entity = entity.substring(1);
                        entity = entity.substring(0, entity.length() - 2);

                        JSONObject jsonData = new JSONObject(entity);
                        String value = (String) jsonData.get("self");

                        nodes.add(URI.create(value));
                    }
                    Vals.put(a.Name, rs.getString(a.Name));
                }
                URI newNode = AddNode(Vals, table.Attr, table.TableName);

                for(URI node : nodes){
                    AddRelationship(Vals, table.Attr, table, node, newNode);
                }
                Vals = new HashMap<String, String>();
            }

            //We might want to add an index:
            AddIndex(table);

            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        System.out.println("Finished converting: " + table.TableName);
    }

    /**
     * Adds a relationship to the Neo4j database.
     * @param Vals values for the properties
     * @param Attr table attributes
     * @param table table for inserting
     * @param node1 address of node1
     * @param node2 address of node2
     */
    public void AddRelationship(Map<String, String> Vals, ArrayList<Attribute> Attr,
                                Table table, URI node1, URI node2) {
        final String nodeEntryPointUri = Neo4jConnectionString + "cypher";

        String[] n1 = node1.toString().split("/");
        String[] n2 = node2.toString().split("/");
        String tname = table.TableName.replace(' ', '_');

        WebResource resource = Client.create()
                .resource(nodeEntryPointUri);
        StringBuilder sb = new StringBuilder();
        sb.append("{ ");

        sb.append("\"query\" : \"");

//      START a=node(...), b=node(...)
//      CREATE a-[r:CONNECTED_TO]-b
//      SET r.att = val
//      RETURN r

        sb.append("START a=node(");

        sb.append(n1[n1.length - 1]);
        sb.append("), b=node(");
        sb.append(n2[n2.length - 1]);
        sb.append(") CREATE a-[r:");
        sb.append(tname);
        sb.append("]-b SET ");

        for (int i = 0; i < Attr.size(); i++) {
            Attribute property = Attr.get(i);
            sb.append("r." + property.Name + "={" + property.Name + "}");
            if (i + 1 < Attr.size()) sb.append(", ");
        }

        sb.append(" RETURN r");

        sb.append("\", \"params\" : { ");

        for (int i = 0; i < Attr.size(); i++) {
            Attribute property = Attr.get(i);
            sb.append("\"" + property.Name + "\" : \"" + Vals.get(property.Name) + "\"");
            if (i + 1 < Attr.size()) sb.append(", ");
        }

        sb.append(" }");
        sb.append(" }");

        ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .entity(sb.toString())
                .post(ClientResponse.class);

        //We might want to add a Label to the relationship:
        //AddLabel(, location);
        System.out.println(String.format(
                "POST to [%s], status code [%d], Relationship from [%s] to [%s]",
                nodeEntryPointUri, response.getStatus(), "node(" + n1[n1.length - 1] + ")", "node(" + n2[n2.length - 1] + ")"));

        response.close();
    }

    /**
     * Adds a node to the Neo4j database.
     * @param Vals values for the properties
     * @param Attr table attributes
     * @param TableName table for inserting
     * @return the address of the new node
     */
    public URI AddNode(Map<String, String> Vals, ArrayList<Attribute> Attr, String TableName) {
        final String nodeEntryPointUri = Neo4jConnectionString + "node";

        WebResource resource = Client.create()
                .resource(nodeEntryPointUri);
        StringBuilder sb = new StringBuilder();
        sb.append("{ ");
        for (int i = 0; i < Attr.size(); i++) {
            Attribute property = Attr.get(i);
            sb.append("\"" + property.Name + "\" : \"" + Vals.get(property.Name) + "\"");
            if (i + 1 < Attr.size()) sb.append(", ");
        }
        sb.append(" }");

        ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .entity(sb.toString())
                .post(ClientResponse.class);

        final URI location = response.getLocation();
        AddLabel(TableName, location);
        System.out.println(String.format(
                "POST to [%s], status code [%d], location header [%s]",
                nodeEntryPointUri, response.getStatus(), location.toString()));
        response.close();

        return location;
    }

    /**
     * Adds an index on the specific table in the Neo4j database.
     * @param table table on which to create the index
     */
    private void AddIndex(Table table) {
        final String nodeEntryPointUri = Neo4jConnectionString + "schema/index/" + table.TableName.replace(' ', '_');

        WebResource resource = Client.create()
                .resource(nodeEntryPointUri);

        StringBuilder sb = new StringBuilder();
        sb.append("{ \"property_keys\" : [ ");
        for (int i = 0; i < table.Keys.size(); i++) {
            String property = table.Keys.get(i);
            sb.append("\"" + property + "\"");
            if (i + 1 < table.Keys.size()) sb.append(", ");
        }
        sb.append(" ] }");

        ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .entity(sb.toString())
                .post(ClientResponse.class);

        final URI location = response.getLocation();
        System.out.println(String.format(
                "POST to [%s], status code [%d]",
                nodeEntryPointUri, response.getStatus()));
        response.close();
    }

    /**
     * Adds a label to the given node.
     * @param TableName table type of the node
     * @param node node address
     */
    private void AddLabel(String TableName, URI node) {
        final String nodeEntryPointUri = node + "/labels";
        final String name = TableName.replace(' ', '_');

        WebResource resource = Client.create()
                .resource(nodeEntryPointUri);

        ClientResponse response = resource.accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .entity("\"" + name + "\"")
                .post(ClientResponse.class);

        final URI location = response.getLocation();
        response.close();
    }
}
