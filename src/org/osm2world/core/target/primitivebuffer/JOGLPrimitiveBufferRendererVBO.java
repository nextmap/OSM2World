package org.osm2world.core.target.primitivebuffer;

import static java.lang.Math.*;
import static javax.media.opengl.GL.*;
import static javax.media.opengl.GL2GL3.GL_DOUBLE;
import static javax.media.opengl.fixedfunc.GLPointerFunc.*;
import static org.osm2world.core.math.GeometryUtil.*;
import static org.osm2world.core.target.common.rendering.OrthoTilesUtil.CardinalDirection.closestCardinal;

import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.media.opengl.GL2;

import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.target.common.Primitive;
import org.osm2world.core.target.common.Primitive.Type;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.target.common.material.Material.Transparency;
import org.osm2world.core.target.common.rendering.Camera;
import org.osm2world.core.target.common.rendering.OrthoTilesUtil.CardinalDirection;
import org.osm2world.core.target.common.rendering.Projection;
import org.osm2world.core.target.jogl.JOGLTarget;

import com.jogamp.common.nio.Buffers;

/**
 * renders the contents of a {@link PrimitiveBuffer} using JOGL.
 * Uses vertex buffer objects (VBO) to speed up the process.
 * 
 * If you don't need the renderer anymore, it's recommended to manually call
 * {@link #freeResources()} to delete the VBOs and other resources.
 */
public class JOGLPrimitiveBufferRendererVBO extends
JOGLPrimitiveBufferRenderer {
	
	private PrimitiveBuffer primitiveBuffer; //keeping this referenced is only necessary because of indexed vertices
	
	/** VBOs with static, non-alphablended geometry for each material */
	private Map<Material, VBOData> vboMap = new HashMap<Material, VBOData>();
	
	/** alphablended primitives, need to be sorted by distance from camera */
	private List<PrimitiveWithMaterial> transparentPrimitives =
			new ArrayList<PrimitiveWithMaterial>();
	
	/**
	 * the camera direction that was the basis for the previous sorting
	 * of {@link #transparentPrimitives}.
	 */
	private CardinalDirection currentPrimitiveSortDirection = null;
	
	/**
	 * class that keeps a VBO id along with associated information
	 */
	private final class VBOData {
		
		/** material associated with this VBO, determines VBO layout */
		private Material material;
		
		/** array with one element containing the VBO id */
		private final int[] id;
		
		/** number of vertices in the vbo */
		private final int vertexCount;
		
		public VBOData(Material material, Collection<Primitive> primitives) {
			
			this.material = material;
			
			vertexCount = countVertices(primitives);
			
			/* create the buffer */
			
			id = new int[1];
			gl.glGenBuffers(1, id, 0);
			
			/* collect the data for the buffer */
			
			DoubleBuffer valueBuffer = Buffers.newDirectDoubleBuffer(
					vertexCount * getValuesPerVertex(material));
			
			for (Primitive primitive : primitiveBuffer.getPrimitives(material)) {
				addPrimitiveToValueBuffer(valueBuffer, primitive);
			}
			
			valueBuffer.rewind();
			
			/* write the data into the buffer */
			
			gl.glBindBuffer(GL_ARRAY_BUFFER, id[0]);
			
			gl.glBufferData(
					GL_ARRAY_BUFFER,
					valueBuffer.capacity() * Buffers.SIZEOF_DOUBLE,
					valueBuffer,
					GL_STATIC_DRAW);
			
		}
		
		/**
		 * returns the number of vertices required to represent a collection
		 * of primitives with individual triangles
		 */
		private int countVertices(Collection<Primitive> primitives) {
			
			int vertexCount = 0;
			
			for (Primitive primitive : primitives) {
				if (primitive.type == Type.TRIANGLES) {
					vertexCount += primitive.indices.length;
				} else {
					vertexCount += 3 * (primitive.indices.length - 2);
				}
			}
			
			return vertexCount;
			
		}
		
		/**
		 * returns the number of values for each vertex
		 * in the vertex buffer layout appropriate for a given material.
		 */
		private int getValuesPerVertex(Material material) {
			
			int numValues = 6; // vertex coordinates and normals
			
			if (material.getTextureDataList() != null) {
				numValues += 2 * material.getTextureDataList().size();
			}
			
			return numValues;
			
		}
		
		/**
		 * put the values for a primitive's vertices into the buffer
		 */
		private void addPrimitiveToValueBuffer(DoubleBuffer buffer,
				Primitive primitive) {
			
			List<VectorXYZ> primVertices =
					new ArrayList<VectorXYZ>(primitive.indices.length);
			
			for (int index : primitive.indices) {
				primVertices.add(primitiveBuffer.getVertex(index));
			}
			
			/*
			 * rearrange the lists of vertices, normals and texture coordinates
			 * to turn triangle strips and triangle fans into separate triangles
			 */
			
			List<VectorXYZ> primNormals = primitive.normals;
			List<List<VectorXZ>> primTexCoordLists = primitive.texCoordLists;
			
			if (primitive.type == Type.TRIANGLE_STRIP) {
				
				primVertices = triangleVertexListFromTriangleStrip(primVertices);
				primNormals = triangleVertexListFromTriangleStrip(primNormals);
				
				if (primTexCoordLists != null) {
					List<List<VectorXZ>> newPrimTexCoordLists = new ArrayList<List<VectorXZ>>();
					for (List<VectorXZ> primTexCoordList : primTexCoordLists) {
						newPrimTexCoordLists.add(triangleVertexListFromTriangleStrip(primTexCoordList));
					}
					primTexCoordLists = newPrimTexCoordLists;
				}
				
			} else if (primitive.type == Type.TRIANGLE_FAN) {
				
				primVertices = triangleVertexListFromTriangleFan(primVertices);
				primNormals = triangleVertexListFromTriangleFan(primNormals);
				
				if (primTexCoordLists != null) {
					List<List<VectorXZ>> newPrimTexCoordLists = new ArrayList<List<VectorXZ>>();
					for (List<VectorXZ> primTexCoordList : primTexCoordLists) {
						newPrimTexCoordLists.add(triangleVertexListFromTriangleFan(primTexCoordList));
					}
					primTexCoordLists = newPrimTexCoordLists;
				}
				
			}
			
			/* put the values into the buffer, in the right order */
			
			for (int i = 0; i < primVertices.size(); i++) {
				
				assert (primTexCoordLists == null
						&& material.getNumTextureLayers() == 0)
					|| (primTexCoordLists != null
						&& primTexCoordLists.size() == material.getNumTextureLayers())
					: "WorldModules need to provide the correct number of tex coords";
				
				if (primTexCoordLists == null && material.getNumTextureLayers() > 0) {
					System.out.println(material);
				}
					
				for (int t = 0; t < material.getNumTextureLayers(); t++) {
					VectorXZ textureCoord =	primTexCoordLists.get(t).get(i);
					buffer.put(textureCoord.x);
					buffer.put(textureCoord.z);
				}
				
				VectorXYZ n = primNormals.get(i);
				buffer.put(n.x);
				buffer.put(n.y);
				buffer.put(-n.z);
				
				VectorXYZ v = primVertices.get(i);
				buffer.put(v.x);
				buffer.put(v.y);
				buffer.put(-v.z);
				
			}
			
		}
		
		public void render() {
			
			for (int i = 0; i < JOGLTarget.MAX_TEXTURE_LAYERS; i++) {
				
				gl.glClientActiveTexture(JOGLTarget.getGLTextureConstant(i));
				
				if (i >= material.getNumTextureLayers()) {
					
					gl.glDisableClientState(GL_TEXTURE_COORD_ARRAY);
					
				} else {
					
					gl.glEnableClientState(GL_TEXTURE_COORD_ARRAY);
					
				}
				
			}
			
			gl.glClientActiveTexture(JOGLTarget.getGLTextureConstant(0));
			
			JOGLTarget.setMaterial(gl, material, textureManager);
			
			gl.glBindBuffer(GL_ARRAY_BUFFER, id[0]);
			
			setPointerLayout();
			
			gl.glDrawArrays(GL_TRIANGLES, 0, vertexCount);
			
		}

		private void setPointerLayout() {
			
			int stride = Buffers.SIZEOF_DOUBLE * getValuesPerVertex(material);
			
			int offset = 0;
			
			for (int i = 0; i < material.getNumTextureLayers(); i++) {
				
				gl.glClientActiveTexture(JOGLTarget.getGLTextureConstant(i));
				gl.glEnableClientState(GL_TEXTURE_COORD_ARRAY);
				
				gl.glActiveTexture(JOGLTarget.getGLTextureConstant(i));
				gl.glTexCoordPointer(2, GL_DOUBLE, stride, offset);
				
				offset += 2 * Buffers.SIZEOF_DOUBLE;
				
			}
			
			gl.glVertexPointer(3, GL_DOUBLE, stride, offset + Buffers.SIZEOF_DOUBLE * 3);
			gl.glNormalPointer(GL_DOUBLE, stride, offset);
			
		}

		public void delete() {
			gl.glDeleteBuffers(id.length, id, 0);
		}
		
	}
	
	
	private static final class PrimitiveWithMaterial {
		
		public final Primitive primitive;
		public final Material material;
		
		private PrimitiveWithMaterial(Primitive primitive, Material material) {
			this.primitive = primitive;
			this.material = material;
		}
		
	}
	
	public JOGLPrimitiveBufferRendererVBO(GL2 gl, PrimitiveBuffer primitiveBuffer) {
		
		super(gl);
		
		this.primitiveBuffer = primitiveBuffer;
		
		primitiveBuffer.optimize();
		
		for (Material material : primitiveBuffer.getMaterials()) {
			
			if (material.getTransparency() == Transparency.TRUE) {
				
				for (Primitive primitive : primitiveBuffer.getPrimitives(material)) {
					transparentPrimitives.add(
							new PrimitiveWithMaterial(primitive, material));
				}
				
			} else {
				
				vboMap.put(material, new VBOData(material,
						primitiveBuffer.getPrimitives(material)));
				
			}
			
		}
		
	}
	
	private void renderPrimitive(GL2 gl, PrimitiveBuffer primitiveBuffer,
			Primitive primitive) {
		
		List<VectorXYZ> vertices =
				new ArrayList<VectorXYZ>(primitive.indices.length);
		
		for (int index : primitive.indices) {
			vertices.add(primitiveBuffer.getVertex(index));
		}
		
		JOGLTarget.drawPrimitive(gl, JOGLTarget.getGLConstant(primitive.type),
				vertices, primitive.normals, primitive.texCoordLists);
		
	}
	
	@Override
	public void render(final Camera camera, final Projection projection) {
		
		/* render static geometry */
		
		gl.glEnableClientState(GL_VERTEX_ARRAY);
		gl.glEnableClientState(GL_NORMAL_ARRAY);
		
		for (Material material : primitiveBuffer.getMaterials()) {
			
			VBOData vboData = vboMap.get(material);
			
			if (vboData != null) {
				vboData.render();
			}
			
		}
		
		gl.glDisableClientState(GL_VERTEX_ARRAY);
		gl.glDisableClientState(GL_NORMAL_ARRAY);
		
		for (int t = 0; t < JOGLTarget.MAX_TEXTURE_LAYERS; t++) {
			gl.glClientActiveTexture(JOGLTarget.getGLTextureConstant(t));
			gl.glDisableClientState(GL_TEXTURE_COORD_ARRAY);
		}
		
		gl.glBindBuffer(GL_ARRAY_BUFFER, 0);
		
		
		/* render transparent primitives back-to-front */
		
		sortPrimitivesBackToFront(camera, projection);
		
		Material previousMaterial = null;
		
		for (PrimitiveWithMaterial p : transparentPrimitives) {
			
			if (!p.material.equals(previousMaterial)) {
				JOGLTarget.setMaterial(gl, p.material, textureManager);
				previousMaterial = p.material;
			}
			
			renderPrimitive(gl, primitiveBuffer, p.primitive);
			
		}
		
	}
	
	private void sortPrimitivesBackToFront(final Camera camera,
			final Projection projection) {
		
		if (projection.isOrthographic() &&
				abs(camera.getViewDirection().xz().angle() % (PI/2)) < 0.01 ) {
			
			/* faster sorting for cardinal directions */
			
			CardinalDirection closestCardinal = closestCardinal(camera.getViewDirection().xz().angle());
			
			if (closestCardinal.isOppositeOf(currentPrimitiveSortDirection)) {
				
				Collections.reverse(transparentPrimitives);
				
			} else if (closestCardinal != currentPrimitiveSortDirection) {
				
				Comparator<PrimitiveWithMaterial> comparator = null;
				
				switch(closestCardinal) {
				
				case N:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p2).z, primitivePos(p1).z);
						}
					};
					break;
					
				case E:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p2).x, primitivePos(p1).x);
						}
					};
					break;
					
				case S:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p1).z, primitivePos(p2).z);
						}
					};
					break;
					
				case W:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p1).x, primitivePos(p2).x);
						}
					};
					break;
					
				}
				
				Collections.sort(transparentPrimitives, comparator);
				
			}
			
			currentPrimitiveSortDirection = closestCardinal;
			
		} else {
			
			/* sort based on distance to camera */
			
			Collections.sort(transparentPrimitives, new Comparator<PrimitiveWithMaterial>() {
				@Override
				public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
					return Double.compare(
							distanceToCameraSq(camera, p2),
							distanceToCameraSq(camera, p1));
				}
			});
			
			currentPrimitiveSortDirection = null;
			
		}
		
	}
	
	private double distanceToCameraSq(Camera camera, PrimitiveWithMaterial p) {
		return primitivePos(p).distanceToSquared(camera.getPos());
	}
	
	private VectorXYZ primitivePos(PrimitiveWithMaterial p) {
		
		double sumX = 0, sumY = 0, sumZ = 0;
		
		for (int index : p.primitive.indices) {
			VectorXYZ v = primitiveBuffer.getVertex(index);
			sumX += v.x;
			sumY += v.y;
			sumZ += v.z;
		}
		
		return new VectorXYZ(sumX / p.primitive.indices.length,
				sumY / p.primitive.indices.length,
				sumZ / p.primitive.indices.length);
		
	}
	
	@Override
	public void freeResources() {
		
		if (vboMap != null) {
			for (VBOData vbo : vboMap.values()) {
				vbo.delete();
			}
			vboMap = null;
		}
		
		super.freeResources();
		
	}
	
}