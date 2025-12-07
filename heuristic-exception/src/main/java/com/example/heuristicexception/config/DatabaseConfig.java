package com.example.heuristicexception.config;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Configuration
public class DatabaseConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.db1")
    public DataSourceProperties db1Properties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource db1DataSource(@Qualifier("db1Properties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(com.zaxxer.hikari.HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSourceTransactionManager db1TxManager(@Qualifier("db1DataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @ConfigurationProperties("spring.datasource.db2")
    public DataSourceProperties db2Properties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource db2DataSource(@Qualifier("db2Properties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(com.zaxxer.hikari.HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.db2root")
    public DataSourceProperties db2RootProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource db2RootDataSource(@Qualifier("db2RootProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(com.zaxxer.hikari.HikariDataSource.class).build();
    }

    @Bean
    public DataSourceTransactionManager db2TxManager(@Qualifier("db2DataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public org.springframework.transaction.PlatformTransactionManager compositeTxManager(
            @Qualifier("db1TxManager") org.springframework.transaction.PlatformTransactionManager db1,
            @Qualifier("db2TxManager") org.springframework.transaction.PlatformTransactionManager db2) {
        return new com.example.heuristicexception.tx.CompositeTransactionManager(java.util.List.of(db1, db2));
    }

    @Bean
    public JdbcTemplate db1JdbcTemplate(@Qualifier("db1DataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public JdbcTemplate db2JdbcTemplate(@Qualifier("db2DataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Configuration
    @MapperScan(basePackages = "com.example.heuristicexception.mapper.db1", sqlSessionTemplateRef = "db1SessionTemplate")
    static class Db1MyBatisConfig {
        @Bean
        public SqlSessionFactory db1SqlSessionFactory(@Qualifier("db1DataSource") DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/db1/*.xml"));
            return factoryBean.getObject();
        }

        @Bean
        public SqlSessionTemplate db1SessionTemplate(@Qualifier("db1SqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }
    }

    @Configuration
    @MapperScan(basePackages = "com.example.heuristicexception.mapper.db2", sqlSessionTemplateRef = "db2SessionTemplate")
    static class Db2MyBatisConfig {
        @Bean
        public SqlSessionFactory db2SqlSessionFactory(@Qualifier("db2DataSource") DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/db2/*.xml"));
            return factoryBean.getObject();
        }

        @Bean
        public SqlSessionTemplate db2SessionTemplate(@Qualifier("db2SqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }
    }
}
