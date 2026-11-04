# syntax=docker/dockerfile:1.7
# PySpark needs a JVM: Temurin 21 JRE plus the distribution's Python 3.12.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv && rm -rf /var/lib/apt/lists/* \
    && groupadd --system batch && useradd --system --gid batch --create-home --home-dir /home/batch batch
WORKDIR /opt/catalog-batch
COPY requirements-lock.txt pyproject.toml ./
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir -r requirements-lock.txt
COPY catalog_batch catalog_batch
RUN /opt/venv/bin/pip install --no-cache-dir --no-deps .
USER batch
ENV PATH=/opt/venv/bin:$PATH PYSPARK_PYTHON=/opt/venv/bin/python SPARK_MASTER=local[*]
ENTRYPOINT ["python", "-m", "catalog_batch.cli"]
CMD ["--help"]
