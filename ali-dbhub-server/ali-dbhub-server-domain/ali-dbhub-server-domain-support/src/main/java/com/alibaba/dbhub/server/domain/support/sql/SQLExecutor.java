/**
 * alibaba.com Inc.
 * Copyright (c) 2004-2022 All Rights Reserved.
 */
package com.alibaba.dbhub.server.domain.support.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.alibaba.dbhub.server.domain.support.enums.CellTypeEnum;
import com.alibaba.dbhub.server.domain.support.model.Cell;
import com.alibaba.dbhub.server.domain.support.model.ExecuteResult;
import com.alibaba.dbhub.server.domain.support.model.Procedure;
import com.alibaba.dbhub.server.domain.support.model.Table;
import com.alibaba.dbhub.server.domain.support.model.TableColumn;
import com.alibaba.dbhub.server.domain.support.model.TableIndex;
import com.alibaba.dbhub.server.domain.support.model.TableIndexColumn;
import com.alibaba.dbhub.server.tools.base.constant.EasyToolsConstant;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Dbhub 统一数据库连接管理
 * TODO 长时间不用连接可以关闭，待优化
 *
 * @author jipengfei
 * @version : DbhubDataSource.java
 */
@Slf4j
public class SQLExecutor {
    /**
     * 全局单例
     */
    private static final SQLExecutor INSTANCE = new SQLExecutor();

    private SQLExecutor() {}

    public static SQLExecutor getInstance() {
        return INSTANCE;
    }

    public Connection getConnection() throws SQLException {
        return DbhubContext.getConnection();
    }

    public void close() {
    }

    /**
     * 执行sql
     *
     * @param sql
     * @param function
     * @return
     */

    public <R> R executeSql(String sql, Function<ResultSet, R> function) {
        if (StringUtils.isEmpty(sql)) {
            return null;
        }
        log.info("execute:{}", sql);
        Statement stmt = null;
        try {
            stmt = getConnection().createStatement();
            boolean query = stmt.execute(sql);
            // 代表是查询
            if (query) {
                ResultSet rs = null;
                try {
                    rs = stmt.getResultSet();
                    return function.apply(rs);
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return null;
    }

    /**
     * 执行sql
     *
     * @param sql
     * @param pageSize
     * @return
     * @throws SQLException
     */
    public ExecuteResult execute(final String sql, Integer pageSize, Connection connection) throws SQLException {
        Assert.notNull(sql, "SQL must not be null");
        log.info("execute:{}", sql);

        ExecuteResult executeResult = ExecuteResult.builder().sql(sql).success(Boolean.TRUE).build();
        Statement stmt = null;
        try {
            stmt = connection.createStatement();
            boolean query = stmt.execute(sql.replaceFirst(";", ""));
            executeResult.setDescription("执行成功");
            // 代表是查询
            if (query) {
                ResultSet rs = null;
                try {
                    rs = stmt.getResultSet();
                    // 获取有几列
                    ResultSetMetaData resultSetMetaData = rs.getMetaData();
                    int col = resultSetMetaData.getColumnCount();

                    // 获取header信息
                    List<Cell> headerList = Lists.newArrayListWithExpectedSize(col);
                    executeResult.setHeaderList(headerList);
                    for (int i = 1; i <= col; i++) {
                        headerList.add(Cell.builder().type(CellTypeEnum.STRING.getCode())
                            .stringValue(resultSetMetaData.getColumnName(i)).build());
                    }

                    // 获取数据信息
                    List<List<Cell>> dataList = Lists.newArrayList();
                    executeResult.setDataList(dataList);

                    // 分页大小
                    executeResult.setHasNextPage(Boolean.FALSE);
                    if (pageSize == null) {
                        pageSize = EasyToolsConstant.MAX_PAGE_SIZE;
                    }
                    int rsSize = 0;
                    while (rs.next()) {
                        List<Cell> row = Lists.newArrayListWithExpectedSize(col);
                        dataList.add(row);
                        for (int i = 1; i <= col; i++) {
                            row.add(com.alibaba.dbhub.server.domain.support.util.JdbcUtils.getResultSetValue(rs, i));
                        }
                        rsSize++;
                        // 到达下一页了
                        if (rsSize >= pageSize) {
                            executeResult.setHasNextPage(Boolean.TRUE);
                            break;
                        }
                    }
                    return executeResult;
                } finally {
                    JdbcUtils.closeResultSet(rs);
                }
            } else {
                // 修改或者其他
                executeResult.setUpdateCount(stmt.getUpdateCount());
            }
        } finally {
            JdbcUtils.closeStatement(stmt);
        }
        return executeResult;
    }

    /**
     * 执行sql
     *
     * @param sql
     * @param pageSize
     * @return
     * @throws SQLException
     */
    public ExecuteResult execute(final String sql, Integer pageSize) throws SQLException {
        return execute(sql, pageSize, getConnection());
    }

    public void connectDatabase(String database) {
        if (StringUtils.isEmpty(database)) {
            return;
        }
        ConnectInfo info = DbhubContext.getConnectInfo();
        switch (info.getDbType()) {
            case MYSQL -> {
                try {
                    execute("use `" + database + "`;", null);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            case SQLSERVER -> {
                try {
                    execute("use " + database + ";", null);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            case ORACLE, H2, SQLITE -> {

            }
            case POSTGRESQL -> {
                //close();
                info.setDatabase(database);
                try {
                    getConnection();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }

            }
            default -> {

            }
        }
    }

    /**
     * 获取所有的数据库
     *
     * @return
     */
    public List<String> databases() {
        List<String> tables = Lists.newArrayList();
        try {
            ResultSet resultSet = getConnection().getMetaData().getCatalogs();
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_CAT"));
            }
        } catch (SQLException e) {
            close();
            throw new RuntimeException(e);
        }
        return tables;
    }

    /**
     * 获取所有的schema
     *
     * @param databaseName
     * @param schemaName
     * @return
     */
    public List<String> schemas(String databaseName, String schemaName) {
        List<String> schemaList = Lists.newArrayList();
        try {
            ResultSet resultSet = getConnection().getMetaData().getSchemas(databaseName, schemaName);
            while (resultSet.next()) {
                schemaList.add(resultSet.getString("TABLE_SCHEM"));
            }
        } catch (SQLException e) {
            close();
            throw new RuntimeException(e);
        }
        return schemaList;
    }

    /**
     * 获取所有的数据库表
     *
     * @param databaseName
     * @param schemaName
     * @param tableName
     * @param types
     * @return
     */
    public List<Table> tables(String databaseName, String schemaName, String tableName, String types[]) {
        List<Table> tables = Lists.newArrayList();
        try {
            ResultSet resultSet = getConnection().getMetaData().getTables(databaseName, schemaName, tableName,
                types);
            while (resultSet.next()) {
                tables.add(buildTable(resultSet));
            }
        } catch (SQLException e) {
            close();
            throw new RuntimeException(e);
        }
        return tables;
    }

    public Table buildTable(ResultSet resultSet) throws SQLException {
        Table table = new Table();
        table.setName(resultSet.getString("TABLE_NAME"));
        table.setComment(resultSet.getString("REMARKS"));
        table.setDatabaseName(resultSet.getString("TABLE_CAT"));
        table.setSchemaName(resultSet.getString("TABLE_SCHEM"));
        table.setType(resultSet.getString("TABLE_TYPE"));
        return table;
    }

    /**
     * 获取所有的数据库表列
     *
     * @param databaseName
     * @param schemaName
     * @param tableName
     * @param columnName
     * @return
     */
    public List<TableColumn> columns(String databaseName, String schemaName, String tableName, String columnName) {
        List<TableColumn> tableColumns = Lists.newArrayList();
        try {
            ResultSet resultSet = getConnection().getMetaData().getColumns(databaseName, schemaName, tableName,
                columnName);
            while (resultSet.next()) {
                tableColumns.add(buildColumn(resultSet));
            }
        } catch (Exception e) {
            close();
            throw new RuntimeException(e);
        }
        return tableColumns;
    }

    private TableColumn buildColumn(ResultSet resultSet) throws SQLException {
        TableColumn tableColumn = new TableColumn();
        tableColumn.setDatabaseName(resultSet.getString("TABLE_CAT"));
        tableColumn.setSchemaName(resultSet.getString("TABLE_SCHEM"));
        tableColumn.setTableName(resultSet.getString("TABLE_NAME"));
        tableColumn.setName(resultSet.getString("COLUMN_NAME"));
        tableColumn.setComment(resultSet.getString("REMARKS"));
        tableColumn.setDefaultValue(resultSet.getString("COLUMN_DEF"));
        tableColumn.setTypeName(resultSet.getString("TYPE_NAME"));
        tableColumn.setColumnSize(resultSet.getInt("COLUMN_SIZE"));
        tableColumn.setDataType(resultSet.getInt("DATA_TYPE"));
        tableColumn.setNullable(resultSet.getInt("NULLABLE") == 1);
        tableColumn.setOrdinalPosition(resultSet.getInt("ORDINAL_POSITION"));
        tableColumn.setAutoIncrement("YES".equals(resultSet.getString("IS_AUTOINCREMENT")));
        tableColumn.setGeneratedColumn("YES".equals(resultSet.getString("IS_GENERATEDCOLUMN")));
        tableColumn.setOrdinalPosition(resultSet.getInt("ORDINAL_POSITION"));
        tableColumn.setDecimalDigits(resultSet.getInt("DECIMAL_DIGITS"));
        tableColumn.setNumPrecRadix(resultSet.getInt("NUM_PREC_RADIX"));
        tableColumn.setCharOctetLength(resultSet.getInt("CHAR_OCTET_LENGTH"));
        return tableColumn;
    }

    /**
     * 获取所有的数据库表索引
     *
     * @param databaseName
     * @param schemaName
     * @param tableName
     * @return
     */
    public List<TableIndex> indexes(String databaseName, String schemaName, String tableName) {
        List<TableIndex> tableIndices = Lists.newArrayList();
        try {
            List<TableIndexColumn> tableIndexColumns = Lists.newArrayList();
            ResultSet resultSet = getConnection().getMetaData().getIndexInfo(databaseName, schemaName, tableName, false,
                false);
            while (resultSet.next()) {
                tableIndexColumns.add(buildTableIndexColumn(resultSet));
            }
            tableIndexColumns.stream().filter(c -> c.getIndexName() != null).collect(
                    Collectors.groupingBy(TableIndexColumn::getIndexName)).entrySet()
                .stream().forEach(entry -> {
                    TableIndex tableIndex = new TableIndex();
                    TableIndexColumn column = entry.getValue().get(0);
                    tableIndex.setName(entry.getKey());
                    tableIndex.setTableName(column.getTableName());
                    tableIndex.setSchemaName(column.getSchemaName());
                    tableIndex.setDatabaseName(column.getDatabaseName());
                    tableIndex.setUnique(!column.getNonUnique());
                    tableIndex.setColumnList(entry.getValue());
                    tableIndices.add(tableIndex);
                });
        } catch (SQLException e) {
            close();
            throw new RuntimeException(e);
        }
        return tableIndices;
    }

    private TableIndexColumn buildTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("COLUMN_NAME"));
        tableIndexColumn.setIndexName(resultSet.getString("INDEX_NAME"));
        tableIndexColumn.setAscOrDesc(resultSet.getString("ASC_OR_DESC"));
        tableIndexColumn.setCardinality(resultSet.getLong("CARDINALITY"));
        tableIndexColumn.setPages(resultSet.getLong("PAGES"));
        tableIndexColumn.setFilterCondition(resultSet.getString("FILTER_CONDITION"));
        tableIndexColumn.setIndexQualifier(resultSet.getString("INDEX_QUALIFIER"));
        // tableIndexColumn.setIndexType(resultSet.getShort("TYPE"));
        tableIndexColumn.setNonUnique(resultSet.getBoolean("NON_UNIQUE"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("ORDINAL_POSITION"));
        tableIndexColumn.setDatabaseName(resultSet.getString("TABLE_CAT"));
        tableIndexColumn.setSchemaName(resultSet.getString("TABLE_SCHEM"));
        tableIndexColumn.setTableName(resultSet.getString("TABLE_NAME"));
        return tableIndexColumn;
    }

    /**
     * 获取所有的函数
     *
     * @param databaseName
     * @param schemaName
     * @return
     */
    public List<com.alibaba.dbhub.server.domain.support.model.Function> functions(String databaseName,
        String schemaName) {
        List<com.alibaba.dbhub.server.domain.support.model.Function> functions = Lists.newArrayList();
        try {
            ResultSet resultSet = getConnection().getMetaData().getFunctions(databaseName, schemaName, null);
            while (resultSet.next()) {
                functions.add(buildFunction(resultSet));
            }
        } catch (Exception e) {
            close();
            throw new RuntimeException(e);
        }
        return functions;
    }

    /**
     * 获取所有的存储过程
     *
     * @param databaseName
     * @param schemaName
     * @return
     */
    public List<Procedure> procedures(String databaseName, String schemaName) {
        List<Procedure> procedures = Lists.newArrayList();
        try {
            ResultSet resultSet = getConnection().getMetaData().getProcedures(databaseName, schemaName, null);
            while (resultSet.next()) {
                procedures.add(buildProcedure(resultSet));
            }
        } catch (Exception e) {
            close();
            throw new RuntimeException(e);
        }
        return procedures;
    }

    private Procedure buildProcedure(ResultSet resultSet) {
        Procedure procedure = new Procedure();
        try {
            procedure.setDatabaseName(resultSet.getString("PROCEDURE_CAT"));
            procedure.setSchemaName(resultSet.getString("PROCEDURE_SCHEM"));
            procedure.setProcedureName(resultSet.getString("PROCEDURE_NAME"));
            procedure.setRemarks(resultSet.getString("REMARKS"));
            procedure.setProcedureType(resultSet.getShort("PROCEDURE_TYPE"));
            procedure.setSpecificName(resultSet.getString("SPECIFIC_NAME"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return procedure;
    }

    private com.alibaba.dbhub.server.domain.support.model.Function buildFunction(ResultSet resultSet) {
        com.alibaba.dbhub.server.domain.support.model.Function function
            = new com.alibaba.dbhub.server.domain.support.model.Function();
        try {
            function.setDatabaseName(resultSet.getString("FUNCTION_CAT"));
            function.setSchemaName(resultSet.getString("FUNCTION_SCHEM"));
            function.setFunctionName(resultSet.getString("FUNCTION_NAME"));
            function.setRemarks(resultSet.getString("REMARKS"));
            function.setFunctionType(resultSet.getShort("FUNCTION_TYPE"));
            function.setSpecificName(resultSet.getString("SPECIFIC_NAME"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return function;
    }

}
