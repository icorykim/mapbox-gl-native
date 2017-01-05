#include "sqlite3.hpp"

#include <QSqlDatabase>
#include <QSqlError>
#include <QSqlQuery>
#include <QStringList>
#include <QVariant>

#include <cassert>
#include <cstring>
#include <cstdio>
#include <chrono>

#include <mbgl/util/chrono.hpp>
#include <mbgl/util/logging.hpp>
#include <mbgl/util/optional.hpp>
#include <mbgl/util/string.hpp>

static uint32_t count = 0;

namespace mapbox {
namespace sqlite {

void checkQueryError(const QSqlQuery& query) {
    QSqlError lastError = query.lastError();
    if (lastError.type() != QSqlError::NoError) {
        throw Exception { lastError.type(), lastError.text().toStdString() };
    }
}

void checkDatabaseError(const QSqlDatabase &db) {
    QSqlError lastError = db.lastError();
    if (lastError.type() != QSqlError::NoError) {
        throw Exception { lastError.type(), lastError.text().toStdString() };
    }
}

class DatabaseImpl {
public:
    DatabaseImpl(const char* filename, int flags)
            : db(QSqlDatabase::addDatabase("QSQLITE", QString::fromStdString(mbgl::util::toString(count++)))) {
        QString connectOptions = db.connectOptions();
        if (flags & OpenFlag::ReadOnly) {
            if (!connectOptions.isEmpty()) connectOptions.append(';');
            connectOptions.append("QSQLITE_OPEN_READONLY");
        }
        if (flags & OpenFlag::SharedCache) {
            if (!connectOptions.isEmpty()) connectOptions.append(';');
            connectOptions.append("QSQLITE_ENABLE_SHARED_CACHE");
        }

        db.setConnectOptions(connectOptions);
        db.setDatabaseName(QString(filename));

        if (!db.open()) {
            checkDatabaseError(db);
        }
    }

    ~DatabaseImpl() {
        db.close();
        checkDatabaseError(db);
    }

    QSqlDatabase db;
};

class StatementImpl {
public:
    StatementImpl(const QString& sql, const QSqlDatabase& db) : query(db) {
        query.setForwardOnly(true);
        if (!query.prepare(sql)) {
            checkQueryError(query);
        }
    }

    ~StatementImpl() {
        query.clear();
    }

    QSqlQuery query;
};

template <typename T>
using optional = std::experimental::optional<T>;


Database::Database(const std::string& file, int flags)
        : impl(std::make_unique<DatabaseImpl>(file.c_str(), flags)) {
    assert(impl);
}

Database::Database(Database &&other)
        : impl(std::move(other.impl)) {
    assert(impl);
}

Database &Database::operator=(Database &&other) {
    std::swap(impl, other.impl);
    assert(impl);
    return *this;
}

Database::~Database() {
}

Database::operator bool() const {
    return impl.operator bool();
}

void Database::setBusyTimeout(std::chrono::milliseconds timeout) {
    std::string timeoutStr = mbgl::util::toString(timeout.count());
    QString connectOptions = impl->db.connectOptions();
    if (connectOptions.isEmpty()) {
        if (!connectOptions.isEmpty()) connectOptions.append(';');
        connectOptions.append("QSQLITE_BUSY_TIMEOUT=").append(QString::fromStdString(timeoutStr));
    }
    if (impl->db.isOpen()) {
        impl->db.close();
    }
    impl->db.setConnectOptions(connectOptions);
    if (!impl->db.open()) {
        checkDatabaseError(impl->db);
    }
}

void Database::exec(const std::string &sql) {
    QStringList statements = QString::fromStdString(sql).split(';');
    statements.removeAll("\n");
    for (QString statement : statements) {
        statement.append(';');
        QSqlQuery query(impl->db);
        query.setForwardOnly(true);
        query.prepare(statement);
        if (!query.exec()) {
            checkQueryError(query);
        }
    }
}

Statement Database::prepare(const char *query) {
    return Statement(this, query);
}

Statement::Statement(Database *db, const char *sql)
        : impl(std::make_unique<StatementImpl>(QString(sql), db->impl->db)) {
    assert(impl);
}

Statement::Statement(Statement &&other)
        : impl(std::move(other.impl)) {
    assert(impl);
}

Statement &Statement::operator=(Statement &&other) {
    std::swap(impl, other.impl);
    assert(impl);
    return *this;
}

Statement::~Statement() {
}

Statement::operator bool() const {
    return impl.operator bool();
}

template void Statement::bind(int, int8_t);
template void Statement::bind(int, int16_t);
template void Statement::bind(int, int32_t);
template void Statement::bind(int, int64_t);
template void Statement::bind(int, uint8_t);
template void Statement::bind(int, uint16_t);
template void Statement::bind(int, uint32_t);
template void Statement::bind(int, double);
template void Statement::bind(int, bool);

template <typename T>
void Statement::bind(int offset, T value) {
    // Field numbering starts at 0.
    impl->query.bindValue(offset - 1, QVariant::fromValue<T>(value), QSql::In);
    checkQueryError(impl->query);
}

template <>
void Statement::bind(int offset, std::nullptr_t) {
    // Field numbering starts at 0.
    impl->query.bindValue(offset - 1, QVariant());
    checkQueryError(impl->query);
}

template <>
void Statement::bind(int offset, mbgl::Timestamp value) {
    bind(offset, std::chrono::system_clock::to_time_t(value));
}

template <>
void Statement::bind(int offset, optional<std::string> value) {
    if (value)
        bind(offset, *value);
    else
        bind(offset, nullptr);
}

template <>
void Statement::bind(int offset, optional<mbgl::Timestamp> value) {
    if (value)
        bind(offset, *value);
    else
        bind(offset, nullptr);
}

void Statement::bind(int offset, const char* value, std::size_t length, bool /* retain */) {
    // Field numbering starts at 0.
    impl->query.bindValue(offset - 1, QString::fromLatin1(value, length), QSql::In);
    checkQueryError(impl->query);
}

void Statement::bind(int offset, const std::string& value, bool /* retain */) {
    // Field numbering starts at 0.
    impl->query.bindValue(offset - 1, QString::fromStdString(value), QSql::In);
    checkQueryError(impl->query);
}

void Statement::bindBlob(int offset, const void* value, std::size_t length, bool /* retain */) {
    // Field numbering starts at 0.
    impl->query.bindValue(offset - 1, QByteArray(reinterpret_cast<const char*>(value), length), QSql::In | QSql::Binary);
    checkQueryError(impl->query);
}

void Statement::bindBlob(int offset, const std::vector<uint8_t>& value, bool retain) {
    bindBlob(offset, value.data(), value.size(), retain);
}

bool Statement::run() {
    impl->query.setForwardOnly(true);
    QString sql = impl->query.lastQuery();

    if (!impl->query.exec()) {
        checkQueryError(impl->query);
    }

    return impl->query.next();
}

template int8_t Statement::get(int);
template int16_t Statement::get(int);
template int32_t Statement::get(int);
template int64_t Statement::get(int);
template uint8_t Statement::get(int);
template uint16_t Statement::get(int);
template uint32_t Statement::get(int);
template double Statement::get(int);
template bool Statement::get(int);

template <typename T> T Statement::get(int offset) {
    assert(impl->query.isValid());
    QVariant value = impl->query.value(offset);
    checkQueryError(impl->query);
    return value.value<T>();
}

template <> std::vector<uint8_t> Statement::get(int offset) {
    assert(impl->query.isValid());
    QByteArray byteArray = impl->query.value(offset).toByteArray();
    checkQueryError(impl->query);
    std::vector<uint8_t> blob(byteArray.begin(), byteArray.end());
    return blob;
}

template <> mbgl::Timestamp Statement::get(int offset) {
    assert(impl->query.isValid());
    QVariant value = impl->query.value(offset);
    checkQueryError(impl->query);
    return std::chrono::time_point_cast<std::chrono::seconds>(
        std::chrono::system_clock::from_time_t(value.value<std::time_t>()));
}

template <> optional<int64_t> Statement::get(int offset) {
    assert(impl->query.isValid());
    QVariant value = impl->query.value(offset);
    checkQueryError(impl->query);
    if (value.isNull())
        return {};
    return { value.value<int64_t>() };
}

template <> optional<double> Statement::get(int offset) {
    assert(impl->query.isValid());
    QVariant value = impl->query.value(offset);
    checkQueryError(impl->query);
    if (value.isNull())
        return {};
    return { value.value<double>() };
}

template <> std::string Statement::get(int offset) {
    assert(impl->query.isValid());
    QByteArray value = impl->query.value(offset).toByteArray();
    checkQueryError(impl->query);
    return std::string(value.constData(), value.size());
}

template <> optional<std::string> Statement::get(int offset) {
    assert(impl->query.isValid());
    QByteArray value = impl->query.value(offset).toByteArray();
    checkQueryError(impl->query);
    if (value.isNull())
        return {};
    return { std::string(value.constData(), value.size()) };
}

template <>
optional<mbgl::Timestamp> Statement::get(int offset) {
    assert(impl->query.isValid());
    QVariant value = impl->query.value(offset);
    checkQueryError(impl->query);
    if (value.isNull())
        return {};
    return { std::chrono::time_point_cast<mbgl::Seconds>(
        std::chrono::system_clock::from_time_t(value.value<std::time_t>())) };
}

void Statement::reset() {
    // no-op
}

void Statement::clearBindings() {
    // no-op
}

int64_t Statement::lastInsertRowId() const {
    return impl->query.lastInsertId().value<int64_t>();
}

uint64_t Statement::changes() const {
    return impl->query.numRowsAffected();
}

Transaction::Transaction(Database& db_, Mode mode)
        : db(db_) {
    switch (mode) {
    case Deferred:
        db.exec("BEGIN DEFERRED TRANSACTION");
        break;
    case Immediate:
        db.exec("BEGIN IMMEDIATE TRANSACTION");
        break;
    case Exclusive:
        db.exec("BEGIN EXCLUSIVE TRANSACTION");
        break;
    }
}

Transaction::~Transaction() {
    if (needRollback) {
        try {
            rollback();
        } catch (...) {
            // Ignore failed rollbacks in destructor.
        }
    }
}

void Transaction::commit() {
    needRollback = false;
    db.exec("COMMIT TRANSACTION");
}

void Transaction::rollback() {
    needRollback = false;
    db.exec("ROLLBACK TRANSACTION");
}

} // namespace sqlite
} // namespace mapbox
