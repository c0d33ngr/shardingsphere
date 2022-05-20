/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.sharding.route.engine.validator.ddl.impl;

import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.exception.ShardingSphereException;
import org.apache.shardingsphere.infra.metadata.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.schema.ShardingSphereSchema;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.sharding.route.engine.validator.ddl.ShardingDDLStatementValidator;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.apache.shardingsphere.sql.parser.sql.common.segment.ddl.index.IndexSegment;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.DropIndexStatement;
import org.apache.shardingsphere.sql.parser.sql.dialect.handler.ddl.DropIndexStatementHandler;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Sharding drop index statement validator.
 */
public final class ShardingDropIndexStatementValidator extends ShardingDDLStatementValidator<DropIndexStatement> {
    
    @Override
    public void preValidate(final ShardingRule shardingRule, final SQLStatementContext<DropIndexStatement> sqlStatementContext, final List<Object> parameters, final ShardingSphereDatabase database) {
        if (DropIndexStatementHandler.containsExistClause(sqlStatementContext.getSqlStatement())) {
            return;
        }
        String defaultSchema = sqlStatementContext.getDatabaseType().getDefaultSchema(database.getName());
        for (IndexSegment each : sqlStatementContext.getSqlStatement().getIndexes()) {
            ShardingSphereSchema schema = each.getOwner().map(optional -> optional.getIdentifier().getValue())
                    .map(optional -> database.getDatabaseMetaData().getSchema(optional)).orElseGet(() -> database.getDatabaseMetaData().getSchema(defaultSchema));
            if (!isSchemaContainsIndex(schema, each)) {
                throw new ShardingSphereException("Index '%s' does not exist.", each.getIndexName().getIdentifier().getValue());
            }
        }
    }
    
    @Override
    public void postValidate(final ShardingRule shardingRule, final SQLStatementContext<DropIndexStatement> sqlStatementContext, final List<Object> parameters,
                             final ShardingSphereDatabase database, final ConfigurationProperties props, final RouteContext routeContext) {
        Collection<IndexSegment> indexSegments = sqlStatementContext.getSqlStatement().getIndexes();
        Optional<String> logicTableName = DropIndexStatementHandler.getSimpleTableSegment(sqlStatementContext.getSqlStatement()).map(optional -> optional.getTableName().getIdentifier().getValue());
        if (logicTableName.isPresent()) {
            validateDropIndexRouteUnit(shardingRule, routeContext, indexSegments, logicTableName.get());
        } else {
            String defaultSchema = sqlStatementContext.getDatabaseType().getDefaultSchema(database.getName());
            for (IndexSegment each : indexSegments) {
                ShardingSphereSchema schema = each.getOwner().map(optional -> optional.getIdentifier().getValue())
                        .map(optional -> database.getDatabaseMetaData().getSchema(optional)).orElseGet(() -> database.getDatabaseMetaData().getSchema(defaultSchema));
                logicTableName = schema.getAllTableNames().stream().filter(tableName -> schema.get(tableName).getIndexes().containsKey(each.getIndexName().getIdentifier().getValue())).findFirst();
                logicTableName.ifPresent(optional -> validateDropIndexRouteUnit(shardingRule, routeContext, indexSegments, optional));
            }
        }
    }
    
    private void validateDropIndexRouteUnit(final ShardingRule shardingRule, final RouteContext routeContext, final Collection<IndexSegment> indexSegments, final String logicTableName) {
        if (isRouteUnitDataNodeDifferentSize(shardingRule, routeContext, logicTableName)) {
            Collection<String> indexNames = indexSegments.stream().map(each -> each.getIndexName().getIdentifier().getValue()).collect(Collectors.toList());
            throw new ShardingSphereException("DROP INDEX ... statement can not route correctly for indexes %s.", indexNames);
        }
    }
}
